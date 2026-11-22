#!/usr/bin/env python3
"""Validate, export, and present measured CJAE Nitro RAG comparison samples."""

from __future__ import annotations

import argparse
import csv
import json
import math
import platform
import re
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    from .aws_nitro_confidential_benchmark_export import (
        ExportError,
        describe_enclave,
        fetch_document,
    )
except ImportError:
    from aws_nitro_confidential_benchmark_export import (
        ExportError,
        describe_enclave,
        fetch_document,
    )

BENCHMARK_ID = "aws-nitro-confidential-rag-java-vs-python-serialized-proxy"
CSV_FIELDS = (
    "platform",
    "sample",
    "records",
    "elapsed_ms",
    "records_per_second",
    "process_cpu_ms",
    "cpu_percent_of_one_core",
    "process_peak_rss_kib",
    "native_index_bytes",
    "serialized_bytes",
    "prompt_sha256",
    "encryption_round_trip_verified",
)


def _positive_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def validate_document(document: Any) -> None:
    if not isinstance(document, dict) or document.get("benchmark") != BENCHMARK_ID:
        raise ExportError("Unexpected AWS Nitro RAG benchmark identifier.")
    methodology = document.get("methodology")
    if not isinstance(methodology, dict):
        raise ExportError("RAG benchmark methodology is missing.")
    if methodology.get("sameNitroEnclave") is not True:
        raise ExportError("Both workload modes must be measured inside the same Nitro Enclave.")
    if methodology.get("gatewayDisabled") is not True:
        raise ExportError("The external gateway must remain disabled.")
    if methodology.get("everyAnnotatedCallPassedAesGcmRoundTrip") is not True:
        raise ExportError("The @ConfidentialAI AES-GCM round-trip evidence is missing.")
    if methodology.get("startupAttestationPassed") is not True:
        raise ExportError("Fresh startup attestation evidence is missing.")
    record_count = methodology.get("syntheticRecordsPerSample")
    measured = methodology.get("measuredSamples")
    warmups = methodology.get("warmupSamplesExcluded")
    batch_size = methodology.get("pythonSerializationBatchSize")
    if not all(_positive_integer(value) for value in (record_count, measured, warmups, batch_size)):
        raise ExportError("RAG benchmark counts must be positive integers.")
    if record_count < 1_000_000:
        raise ExportError("RAG benchmark must process at least one million synthetic records.")
    java_samples = document.get("javaSamples")
    python_samples = document.get("pythonSamples")
    if not isinstance(java_samples, list) or not isinstance(python_samples, list):
        raise ExportError("Java or Python sample arrays are missing.")
    if len(java_samples) != measured or len(python_samples) != measured:
        raise ExportError("Sample counts do not match the declared measurement count.")
    for index, (java_sample, python_sample) in enumerate(
        zip(java_samples, python_samples), start=1
    ):
        if not isinstance(java_sample, dict) or not isinstance(python_sample, dict):
            raise ExportError(f"RAG sample {index} is not an object.")
        for sample in (java_sample, python_sample):
            for field in ("elapsedNanos", "processCpuNanos", "processPeakRssKiB"):
                if not _positive_integer(sample.get(field)):
                    raise ExportError(f"RAG sample {index} is missing positive {field}.")
            if sample.get("sample") != index:
                raise ExportError(f"RAG sample numbering is invalid at sample {index}.")
            if not isinstance(sample.get("promptSha256"), str) or len(
                sample["promptSha256"]
            ) != 64:
                raise ExportError(f"RAG sample {index} is missing its prompt digest.")
        if not _positive_integer(java_sample.get("nativeIndexBytes")):
            raise ExportError(f"Java sample {index} is missing off-heap index size.")
        if not _positive_integer(python_sample.get("serializedBytes")):
            raise ExportError(f"Python sample {index} did not serialize data.")
        if (
            java_sample.get("topRecordIds") != python_sample.get("topRecordIds")
            or java_sample.get("promptSha256") != python_sample.get("promptSha256")
        ):
            raise ExportError(f"Java/Python retrieval results diverge in sample {index}.")
    if document.get("matchingRetrievalResults") is not True:
        raise ExportError("Benchmark does not certify matching retrieval results.")
    if document.get("everyPromptAesGcmRoundTripVerified") is not True:
        raise ExportError("One or more prompt encryption round trips failed.")
    measured_bytes = sum(sample["serializedBytes"] for sample in python_samples)
    if document.get("totalPythonSerializedBytes") != measured_bytes:
        raise ExportError("Python serialized-byte total does not match raw samples.")


def summarize_samples(samples: list[dict[str, Any]], record_count: int) -> dict[str, Any]:
    latency_ms = [sample["elapsedNanos"] / 1_000_000 for sample in samples]
    throughput = [
        record_count / (sample["elapsedNanos"] / 1_000_000_000)
        for sample in samples
    ]
    cpu_percent = [
        100 * sample["processCpuNanos"] / sample["elapsedNanos"] for sample in samples
    ]
    peak_rss_mib = [sample["processPeakRssKiB"] / 1024 for sample in samples]
    return {
        "sample_count": len(samples),
        "latency_ms": {
            "median": statistics.median(latency_ms),
            "p95": percentile(latency_ms, 95),
            "minimum": min(latency_ms),
            "maximum": max(latency_ms),
        },
        "records_per_second": {
            "median": statistics.median(throughput),
            "minimum": min(throughput),
            "maximum": max(throughput),
        },
        "cpu_percent_of_one_core": {
            "median": statistics.median(cpu_percent),
            "maximum": max(cpu_percent),
        },
        "process_peak_rss_mib": {
            "median": statistics.median(peak_rss_mib),
            "maximum": max(peak_rss_mib),
        },
    }


def percentile(values: list[float], percentage: int) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(percentage / 100 * len(ordered)) - 1)
    return ordered[index]


def build_export(
    document: dict[str, Any],
    enclave: dict[str, Any],
    region: str,
    instance_type: str,
    cpu_model: str,
    image_measurements: dict[str, str],
) -> dict[str, Any]:
    validate_document(document)
    cpu_count = enclave.get("NumberOfCPUs")
    memory_mib = enclave.get("MemoryMiB")
    if not _positive_integer(cpu_count) or not _positive_integer(memory_mib):
        raise ExportError("Nitro enclave CPU and memory allocation metadata is missing.")
    count = document["methodology"]["syntheticRecordsPerSample"]
    java = summarize_samples(document["javaSamples"], count)
    python = summarize_samples(document["pythonSamples"], count)
    latency_speedup = (
        python["latency_ms"]["median"] / java["latency_ms"]["median"]
    )
    throughput_ratio = (
        java["records_per_second"]["median"]
        / python["records_per_second"]["median"]
    )
    return {
        "exported_at_utc": datetime.now(timezone.utc).isoformat(),
        "benchmark": BENCHMARK_ID,
        "comparison_label": "Java FFM vs Python serialized proxy; not Opaque or Apache Spark",
        "region": region,
        "hardware": {
            "instance_type": instance_type,
            "host_cpu_model": cpu_model,
            "enclave_cpu_count": cpu_count,
            "enclave_memory_mib": memory_mib,
            "execution": "Both benchmark paths ran sequentially in the same AWS Nitro Enclave.",
        },
        "enclave": {
            "name": enclave.get("EnclaveName"),
            "cid": enclave.get("EnclaveCID"),
            "state": enclave.get("State"),
            "flags": enclave.get("Flags"),
        },
        "nitro_image_measurements_sha384": image_measurements,
        "methodology": document["methodology"],
        "summary": {
            "synthetic_records_per_sample": count,
            "java": java,
            "python_proxy": python,
            "measured_ratios": {
                "python_latency_divided_by_java_latency": latency_speedup,
                "java_throughput_divided_by_python_throughput": throughput_ratio,
            },
            "targets": {
                "latency_speedup_target": 3.0,
                "throughput_ratio_target": 5.0,
                "latency_target_met": latency_speedup >= 3.0,
                "throughput_target_met": throughput_ratio >= 5.0,
            },
            "retrieval_results_match": document["matchingRetrievalResults"],
            "aes_gcm_prompt_round_trips_verified": document[
                "everyPromptAesGcmRoundTripVerified"
            ],
            "python_serialized_bytes": document["totalPythonSerializedBytes"],
        },
        "raw_samples": {
            "java": document["javaSamples"],
            "python_proxy": document["pythonSamples"],
        },
    }


def collect_image_measurements(eif_path: Path) -> dict[str, str]:
    try:
        output = subprocess.run(
            ["nitro-cli", "describe-eif", "--eif-path", str(eif_path)],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        ).stdout
        result = json.loads(output)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        raise ExportError("Could not read Nitro EIF measurements.") from error
    measurements = result.get("Measurements")
    if not isinstance(measurements, dict):
        raise ExportError("Nitro EIF measurement metadata is missing.")
    pcrs = {
        key: value.lower()
        for key, value in measurements.items()
        if key.startswith("PCR")
        and isinstance(value, str)
        and re.fullmatch(r"[0-9a-fA-F]{96}", value)
    }
    if "PCR0" not in pcrs:
        raise ExportError("Nitro EIF PCR0 measurement is missing.")
    return pcrs


def collect_cpu_model() -> str:
    try:
        for line in Path("/proc/cpuinfo").read_text(encoding="utf-8").splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError as error:
        processor = platform.processor()
        if processor:
            return processor
        raise ExportError("Could not collect host CPU model.") from error
    processor = platform.processor()
    if processor:
        return processor
    raise ExportError("Could not collect host CPU model.")


def write_csv(export: dict[str, Any], path: Path) -> None:
    methodology = export["methodology"]
    record_count = methodology["syntheticRecordsPerSample"]
    verified = export["summary"]["aes_gcm_prompt_round_trips_verified"]
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for platform_name in ("java", "python_proxy"):
            for sample in export["raw_samples"][platform_name]:
                elapsed = sample["elapsedNanos"]
                writer.writerow(
                    {
                        "platform": platform_name,
                        "sample": sample["sample"],
                        "records": record_count,
                        "elapsed_ms": f"{elapsed / 1_000_000:.6f}",
                        "records_per_second": f"{record_count * 1e9 / elapsed:.3f}",
                        "process_cpu_ms": f"{sample['processCpuNanos'] / 1_000_000:.6f}",
                        "cpu_percent_of_one_core": f"{100 * sample['processCpuNanos'] / elapsed:.3f}",
                        "process_peak_rss_kib": sample["processPeakRssKiB"],
                        "native_index_bytes": sample.get("nativeIndexBytes", ""),
                        "serialized_bytes": sample.get("serializedBytes", ""),
                        "prompt_sha256": sample["promptSha256"],
                        "encryption_round_trip_verified": str(verified).lower(),
                    }
                )


def write_markdown(export: dict[str, Any], path: Path) -> None:
    summary = export["summary"]
    java = summary["java"]
    python = summary["python_proxy"]
    ratios = summary["measured_ratios"]
    hardware = export["hardware"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(
            [
                "# AWS Nitro RAG Benchmark: Executive Summary",
                "",
                f"**Generated:** {export['exported_at_utc']}",
                f"**Hardware:** {hardware['instance_type']} in {export['region']}; "
                f"{hardware['enclave_cpu_count']} enclave vCPUs / "
                f"{hardware['enclave_memory_mib']} MiB; {hardware['host_cpu_model']}.",
                "**Comparator:** explicitly labeled Python serialized proxy. "
                "This is not the Opaque product and does not run Apache Spark.",
                "",
                "## Result",
                "",
                f"Each measured sample processed {summary['synthetic_records_per_sample']:,} "
                "deterministically generated synthetic records. Both modes ran sequentially "
                "inside the same Nitro Enclave and produced matching top-k retrieval digests.",
                "",
                "| Metric | Java FFM/off-heap | Python serialized proxy |",
                "|---|---:|---:|",
                f"| Median pipeline latency | {java['latency_ms']['median']:.3f} ms "
                f"(p95 {java['latency_ms']['p95']:.3f} ms) | "
                f"{python['latency_ms']['median']:.3f} ms "
                f"(p95 {python['latency_ms']['p95']:.3f} ms) |",
                f"| Median throughput | {java['records_per_second']['median']:,.0f} records/s | "
                f"{python['records_per_second']['median']:,.0f} records/s |",
                f"| Median process CPU | {java['cpu_percent_of_one_core']['median']:.1f}% of one core | "
                f"{python['cpu_percent_of_one_core']['median']:.1f}% of one core |",
                f"| Median process peak RSS | {java['process_peak_rss_mib']['median']:.1f} MiB | "
                f"{python['process_peak_rss_mib']['median']:.1f} MiB |",
                "",
                f"Measured Python/Java latency ratio: **{ratios['python_latency_divided_by_java_latency']:.2f}x**. "
                f"Measured Java/Python throughput ratio: **{ratios['java_throughput_divided_by_python_throughput']:.2f}x**.",
                f"The pre-registered 3x latency target was "
                f"{'met' if summary['targets']['latency_target_met'] else 'not met'}; "
                f"the 5x throughput target was "
                f"{'met' if summary['targets']['throughput_target_met'] else 'not met'}. "
                "These targets are evaluated from these captured samples, not assumed.",
                "",
                "## Integrity and interpretation",
                "",
                "- Java tokenizes synthetic records, computes deterministic 16-dimensional "
                "hash embeddings in a zeroizing Panama off-heap index, performs cosine top-k "
                "retrieval, and packages the prompt.",
                "- The Python proxy runs the same deterministic data and retrieval logic, "
                "with pickle serialization/deserialization at 10,000-record batch boundaries.",
                "- Every Java/Python result pair must match before output; AES-256-GCM prompt "
                "seal/open round trips are verified for each measured sample.",
                "- Pipeline latency excludes the single enclosing `@ConfidentialAI` "
                "runtime verification. Startup attestation and gateway-disabled status are "
                "reported separately.",
                "- RSS is process high-water RSS: the Java figure includes the worker JVM; "
                "the Python figure covers its subprocess. Treat memory comparisons with that "
                "runtime-scope distinction.",
                "- This measures one synthetic workload on one enclave and a Python proxy. "
                "It does not establish performance versus Opaque, Apache Spark, production "
                "customer data, or a distributed cluster.",
                "",
                "Raw per-sample values are in `samples.csv`; metadata and full provenance are "
                "in `summary.json`.",
            ]
        )
        + "\n",
        encoding="utf-8",
    )


class PdfPage:
    def __init__(self) -> None:
        self.commands: list[str] = []

    def text(self, x: float, y: float, value: str, size: int = 14, bold: bool = False) -> None:
        font = "F2" if bold else "F1"
        safe = value.encode("ascii", "replace").decode("ascii")
        escaped = safe.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        self.commands.append(
            f"BT /{font} {size} Tf {x:.1f} {y:.1f} Td ({escaped}) Tj ET"
        )

    def line(self, x1: float, y1: float, x2: float, y2: float) -> None:
        self.commands.append(
            f"{x1:.1f} {y1:.1f} m {x2:.1f} {y2:.1f} l S"
        )

    def rect(self, x: float, y: float, width: float, height: float, fill: bool) -> None:
        self.commands.append(
            f"{x:.1f} {y:.1f} {width:.1f} {height:.1f} re {'f' if fill else 'S'}"
        )

    def text_lines(
        self, x: float, y: float, lines: list[str], size: int = 14, gap: int = 22
    ) -> None:
        for line in lines:
            self.text(x, y, line, size)
            y -= gap


def write_pdf(export: dict[str, Any], path: Path) -> None:
    summary = export["summary"]
    java = summary["java"]
    python = summary["python_proxy"]
    pages: list[PdfPage] = []

    cover = PdfPage()
    cover.text(60, 440, "CONFIDENTIAL JVM RAG BENCHMARK", 28, True)
    cover.text(60, 395, "AWS Nitro Enclave | synthetic million-record workload", 18)
    cover.text(60, 340, f"Hardware: {export['hardware']['instance_type']}", 16)
    cover.text(60, 310, f"Region: {export['region']}", 16)
    cover.text(60, 280, f"Enclave: {export['enclave']['name']}", 16)
    cover.text(60, 220, "Java 21 Panama off-heap vs Python serialized proxy", 18, True)
    cover.text(60, 185, "Not an Opaque product comparison; not Apache Spark.", 16)
    cover.text(60, 130, f"Measured samples per mode: {java['sample_count']}", 14)
    cover.text(60, 100, f"Generated: {export['exported_at_utc']}", 12)
    pages.append(cover)

    method = PdfPage()
    method.text(48, 490, "1. Workload and protocol", 24, True)
    method.text_lines(
        55,
        445,
        [
            f"{summary['synthetic_records_per_sample']:,} deterministic synthetic records per sample",
            "Tokenize fields; compute 16-D hash embeddings; cosine top-k; package prompt",
            "Java: direct generation and a zeroizing Panama MemorySegment index",
            "Python proxy: equivalent hash/index/search with pickle per 10,000-row batch",
            f"Both modes run sequentially inside the same {export['hardware']['enclave_cpu_count']}-vCPU / "
            f"{export['hardware']['enclave_memory_mib']}-MiB enclave",
            "One enclosing @ConfidentialAI verification; gateway remains disabled",
            "Warm-ups are excluded; each output pair must have identical retrieval digests",
            "AES-GCM seal/open is verified on every measured prompt pair",
        ],
        14,
        36,
    )
    pages.append(method)

    latency = PdfPage()
    latency.text(48, 490, "2. Latency and throughput", 24, True)
    latency.text(90, 440, "Median end-to-end pipeline latency (ms)", 14, True)
    max_latency = max(java["latency_ms"]["median"], python["latency_ms"]["median"], 1)
    for label, values, y in (
        ("Java FFM", java, 390),
        ("Python proxy", python, 340),
    ):
        latency.text(70, y + 8, label, 13)
        width = 610 * values["latency_ms"]["median"] / max_latency
        latency.rect(200, y, width, 24, label == "Java FFM")
        latency.text(820, y + 4, f"{values['latency_ms']['median']:.2f}", 12)
    latency.text(90, 260, "Median throughput (records / second)", 14, True)
    max_rate = max(
        java["records_per_second"]["median"],
        python["records_per_second"]["median"],
        1,
    )
    for label, values, y in (
        ("Java FFM", java, 210),
        ("Python proxy", python, 160),
    ):
        latency.text(70, y + 8, label, 13)
        width = 610 * values["records_per_second"]["median"] / max_rate
        latency.rect(200, y, width, 24, label == "Java FFM")
        latency.text(820, y + 4, f"{values['records_per_second']['median']:,.0f}", 12)
    ratios = summary["measured_ratios"]
    latency.text(
        65,
        90,
        f"Observed ratios: latency {ratios['python_latency_divided_by_java_latency']:.2f}x; "
        f"throughput {ratios['java_throughput_divided_by_python_throughput']:.2f}x",
        14,
        True,
    )
    pages.append(latency)

    resources = PdfPage()
    resources.text(48, 490, "3. CPU and memory", 24, True)
    resources.text(65, 445, "Process CPU (% of one core; median)", 15, True)
    resources.text(
        65,
        415,
        f"Java {java['cpu_percent_of_one_core']['median']:.1f}%  |  "
        f"Python {python['cpu_percent_of_one_core']['median']:.1f}%",
        16,
    )
    resources.text(65, 360, "Peak resident set size (MiB; median)", 15, True)
    resources.text(
        65,
        330,
        f"Java {java['process_peak_rss_mib']['median']:.1f} MiB  |  "
        f"Python {python['process_peak_rss_mib']['median']:.1f} MiB",
        16,
    )
    resources.text_lines(
        65,
        255,
        [
            "CPU is process CPU time divided by sample wall time.",
            "Java RSS includes the Spring worker JVM; Python RSS is the child proxy process.",
            f"Python serialized {summary['python_serialized_bytes']:,} bytes across measured samples.",
            f"The enclave allocation was {export['hardware']['enclave_cpu_count']} vCPUs and "
            f"{export['hardware']['enclave_memory_mib']} MiB for both sequential modes.",
            "Memory figures are process high-water values, not whole-instance utilization.",
        ],
        13,
        28,
    )
    pages.append(resources)

    integrity = PdfPage()
    integrity.text(48, 490, "4. Confidentiality and result integrity", 24, True)
    integrity.text_lines(
        65,
        435,
        [
            f"Fresh startup attestation/key release: {export['methodology']['startupAttestationPassed']}",
            f"Gateway disabled: {export['methodology']['gatewayDisabled']}",
            f"Matching retrieval results: {summary['retrieval_results_match']}",
            f"AES-GCM prompt round trips: {summary['aes_gcm_prompt_round_trips_verified']}",
            f"Nitro EIF PCR0: {export['nitro_image_measurements_sha384'].get('PCR0', 'not reported')}",
            "No real customer records, prompts, or ciphertext were exported.",
        ],
        14,
        34,
    )
    pages.append(integrity)

    limits = PdfPage()
    limits.text(48, 490, "5. Findings and limitations", 24, True)
    latency_met = summary["targets"]["latency_target_met"]
    throughput_met = summary["targets"]["throughput_target_met"]
    limits.text_lines(
        65,
        440,
        [
            f"3x latency target: {'MET' if latency_met else 'NOT MET'} (measured samples only)",
            f"5x throughput target: {'MET' if throughput_met else 'NOT MET'} (measured samples only)",
            "The comparator is a local Python/pickle proxy, not Opaque or Apache Spark.",
            "A single synthetic workload does not generalize to production RAG, embeddings, or LLMs.",
            "Do not infer distributed-cluster cost or customer-data performance from this experiment.",
            "Pipeline timing excludes the enclosing runtime verification; encryption is independently verified.",
            "Use raw CSV, JSON metadata, exact PCRs, and the test harness for reproduction.",
        ],
        14,
        36,
    )
    pages.append(limits)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(serialize_pdf(pages))


def serialize_pdf(pages: list[PdfPage]) -> bytes:
    objects: list[bytes] = []

    def add_object(value: bytes) -> int:
        objects.append(value)
        return len(objects)

    catalog_id = add_object(b"")
    pages_id = add_object(b"")
    regular_font_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    bold_font_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")
    page_ids: list[int] = []
    for page in pages:
        stream = ("\n".join(page.commands) + "\n").encode("ascii")
        stream_id = add_object(
            b"<< /Length "
            + str(len(stream)).encode("ascii")
            + b" >>\nstream\n"
            + stream
            + b"endstream"
        )
        page_id = add_object(
            (
                f"<< /Type /Page /Parent {pages_id} 0 R /MediaBox [0 0 960 540] "
                f"/Resources << /Font << /F1 {regular_font_id} 0 R "
                f"/F2 {bold_font_id} 0 R >> >> /Contents {stream_id} 0 R >>"
            ).encode("ascii")
        )
        page_ids.append(page_id)
    objects[catalog_id - 1] = f"<< /Type /Catalog /Pages {pages_id} 0 R >>".encode("ascii")
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects[pages_id - 1] = (
        f"<< /Type /Pages /Kids [{kids}] /Count {len(page_ids)} >>".encode("ascii")
    )
    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for object_id, value in enumerate(objects, start=1):
        offsets.append(len(output))
        output.extend(f"{object_id} 0 obj\n".encode("ascii"))
        output.extend(value)
        output.extend(b"\nendobj\n")
    xref_offset = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        (
            f"trailer\n<< /Size {len(objects) + 1} /Root {catalog_id} 0 R >>\n"
            f"startxref\n{xref_offset}\n%%EOF\n"
        ).encode("ascii")
    )
    return bytes(output)


def write_exports(export: dict[str, Any], output_dir: Path) -> tuple[Path, ...]:
    output_dir.mkdir(parents=True, exist_ok=True)
    summary_path = output_dir / "summary.json"
    csv_path = output_dir / "samples.csv"
    report_path = output_dir / "executive_summary.md"
    pdf_path = output_dir / "academic_presentation.pdf"
    summary_path.write_text(
        json.dumps(export, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    write_csv(export, csv_path)
    write_markdown(export, report_path)
    write_pdf(export, pdf_path)
    return summary_path, csv_path, report_path, pdf_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cid", type=int)
    parser.add_argument("--port", type=int, default=8002)
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--enclave-name", default="cjae-worker-confidential-benchmark")
    parser.add_argument("--eif", type=Path, default=Path("/opt/cjae/worker-confidential-rag.eif"))
    parser.add_argument("--region", default="eu-north-1")
    parser.add_argument("--instance-type", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        enclave = describe_enclave(args.enclave_name)
        cid = args.cid if args.cid is not None else int(enclave["EnclaveCID"])
        document = fetch_document(cid, args.port, args.timeout)
        validate_document(document)
        export = build_export(
            document,
            enclave,
            args.region,
            args.instance_type,
            collect_cpu_model(),
            collect_image_measurements(args.eif),
        )
        paths = write_exports(export, args.output_dir)
    except (ExportError, OSError, ValueError, KeyError) as error:
        print(f"RAG benchmark export failed: {error}", file=sys.stderr)
        return 2
    for path in paths:
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
