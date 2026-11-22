#!/usr/bin/env python3
"""Export measured annotated AES-GCM benchmark samples from an AWS Nitro enclave."""

from __future__ import annotations

import argparse
import csv
import json
import socket
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

MAX_RESPONSE_BYTES = 2_000_000
SAMPLE_FIELDS = (
    "sample",
    "timestamp_utc",
    "plaintext_bytes",
    "encrypted_context_bytes",
    "annotated_call_ms",
    "aes_gcm_encrypt_ms",
    "aes_gcm_decrypt_ms",
    "round_trip_verified",
)


class ExportError(RuntimeError):
    """Raised when live benchmark results are missing or invalid."""


def describe_enclave(enclave_name: str) -> dict[str, Any]:
    try:
        output = subprocess.run(
            ["nitro-cli", "describe-enclaves"],
            check=True,
            capture_output=True,
            text=True,
            timeout=15,
        ).stdout
        enclaves = json.loads(output)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        raise ExportError("Could not read Nitro enclave metadata.") from error
    matches = [
        enclave
        for enclave in enclaves
        if enclave.get("EnclaveName") == enclave_name and enclave.get("State") == "RUNNING"
    ]
    if len(matches) != 1:
        raise ExportError(
            f"Expected exactly one running enclave named {enclave_name!r}; "
            f"found {len(matches)}."
        )
    return matches[0]


def read_response(connection: socket.socket) -> tuple[int, bytes]:
    response = bytearray()
    header_end = -1
    content_length: Optional[int] = None
    while len(response) <= MAX_RESPONSE_BYTES:
        chunk = connection.recv(4096)
        if not chunk:
            break
        response.extend(chunk)
        if header_end < 0:
            header_end = response.find(b"\r\n\r\n")
            if header_end >= 0:
                headers = bytes(response[:header_end]).decode("ascii", "strict")
                for line in headers.split("\r\n")[1:]:
                    if line.lower().startswith("content-length:"):
                        content_length = int(line.split(":", 1)[1].strip())
                        break
        if header_end >= 0 and content_length is not None:
            if len(response) >= header_end + 4 + content_length:
                break
    if len(response) > MAX_RESPONSE_BYTES:
        raise ExportError("Benchmark response exceeded the configured size limit.")
    if header_end < 0 or content_length is None:
        raise ExportError("Benchmark endpoint returned incomplete HTTP headers.")
    if len(response) < header_end + 4 + content_length:
        raise ExportError("Benchmark endpoint returned an incomplete response body.")
    headers = bytes(response[:header_end]).decode("ascii", "strict").split("\r\n")
    try:
        status = int(headers[0].split()[1])
    except (IndexError, ValueError) as error:
        raise ExportError("Benchmark endpoint returned an invalid HTTP status.") from error
    return status, bytes(response[header_end + 4 : header_end + 4 + content_length])


def fetch_document(cid: int, port: int, timeout: float) -> dict[str, Any]:
    if not hasattr(socket, "AF_VSOCK"):
        raise ExportError("Run the exporter on the Linux Nitro parent with AF_VSOCK.")
    with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as connection:
        connection.settimeout(timeout)
        connection.connect((cid, port))
        connection.sendall(
            b"GET /benchmark/results HTTP/1.1\r\n"
            b"Host: localhost\r\n"
            b"Connection: close\r\n\r\n"
        )
        status, body = read_response(connection)
    if status != 200:
        raise ExportError(f"Enclave benchmark endpoint returned HTTP {status}.")
    try:
        document = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ExportError("Enclave benchmark endpoint returned invalid JSON.") from error
    return document


def fetch_results(cid: int, port: int, timeout: float) -> dict[str, Any]:
    document = fetch_document(cid, port, timeout)
    validate_document(document)
    return document


def validate_document(document: Any) -> None:
    if not isinstance(document, dict):
        raise ExportError("Benchmark result must be a JSON object.")
    if document.get("benchmark") != "aws-nitro-confidential-annotation-aes-gcm-roundtrip":
        raise ExportError("Unexpected benchmark identifier.")
    methodology = document.get("methodology")
    summary = document.get("summary")
    samples = document.get("samples")
    if not isinstance(methodology, dict) or not isinstance(summary, dict):
        raise ExportError("Benchmark methodology or summary is missing.")
    if methodology.get("gatewayDisabled") is not True:
        raise ExportError("Benchmark metadata does not confirm the gateway stayed disabled.")
    if methodology.get("everyAnnotatedCallPassedAesGcmRoundTrip") is not True:
        raise ExportError("Benchmark metadata does not confirm annotated AES-GCM round trips.")
    if summary.get("allEncryptionRoundTripsVerified") is not True:
        raise ExportError("Benchmark summary reports one or more unsuccessful encrypted round trips.")
    if not isinstance(samples, list) or not samples:
        raise ExportError("Benchmark contains no raw samples.")
    if summary.get("sampleCount") != len(samples):
        raise ExportError("Benchmark summary count does not match its raw samples.")
    for index, sample in enumerate(samples, start=1):
        if (
            not isinstance(sample, dict)
            or sample.get("sample") != index
            or sample.get("roundTripVerified") is not True
            or not isinstance(sample.get("annotatedCallNanos"), int)
            or not isinstance(sample.get("aesGcmEncryptNanos"), int)
            or not isinstance(sample.get("aesGcmDecryptNanos"), int)
        ):
            raise ExportError(f"Benchmark sample {index} is incomplete or unsuccessful.")


def build_export(
    document: dict[str, Any],
    enclave: dict[str, Any],
    region: str,
    image_digest: str,
    pcrs: dict[str, str],
) -> dict[str, Any]:
    return {
        "exported_at_utc": datetime.now(timezone.utc).isoformat(),
        "benchmark": document["benchmark"],
        "region": region,
        "enclave": {
            "name": enclave.get("EnclaveName"),
            "cid": enclave.get("EnclaveCID"),
            "state": enclave.get("State"),
            "flags": enclave.get("Flags"),
            "cpu_count": enclave.get("NumberOfCPUs"),
            "memory_mib": enclave.get("MemoryMiB"),
        },
        "image_digest": image_digest,
        "pcr_sha384": pcrs,
        "environment": {
            "gateway_enabled": False,
            "execution": "inside AWS Nitro Enclave",
            "annotated_method": "@ConfidentialAI AwsNitroConfidentialBenchmarkOperation.encryptAndVerify",
            "cryptography": "AES-256-GCM secure-context seal and authenticated open using NSM/KMS-released key",
        },
        "measurement_definition": {
            "annotated_call_ms": "Caller-to-return time, including @ConfidentialAI verification and AES-GCM seal/open.",
            "aes_gcm_encrypt_ms": "Time within the annotated method for AES-GCM secure-context sealing.",
            "aes_gcm_decrypt_ms": "Time within the annotated method for AES-GCM authenticated open.",
            "success_criterion": "Every sample reopens its encrypted context and verifies the recovered synthetic payload exactly.",
            "sample_policy": "Synthetic payload values only; no plaintext or ciphertext is exported.",
        },
        "methodology": document["methodology"],
        "summary": document["summary"],
        "samples": document["samples"],
    }


def write_exports(export: dict[str, Any], output_dir: Path) -> tuple[Path, Path, Path]:
    samples_path = output_dir / "samples.csv"
    summary_path = output_dir / "summary.json"
    report_path = output_dir / "report.md"
    output_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for sample in export["samples"]:
        rows.append(
            {
                "sample": sample["sample"],
                "timestamp_utc": sample["timestampUtc"],
                "plaintext_bytes": sample["plaintextBytes"],
                "encrypted_context_bytes": sample["encryptedContextBytes"],
                "annotated_call_ms": f"{sample['annotatedCallNanos'] / 1_000_000:.6f}",
                "aes_gcm_encrypt_ms": f"{sample['aesGcmEncryptNanos'] / 1_000_000:.6f}",
                "aes_gcm_decrypt_ms": f"{sample['aesGcmDecryptNanos'] / 1_000_000:.6f}",
                "round_trip_verified": sample["roundTripVerified"],
            }
        )
    with samples_path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=SAMPLE_FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    summary_path.write_text(
        json.dumps(export, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    summary = export["summary"]
    report = [
        "# AWS Nitro annotated AES-GCM benchmark",
        "",
        f"- Generated: {export['exported_at_utc']}",
        f"- Region: {export['region']}",
        f"- Enclave: {export['enclave']['name']} (CID {export['enclave']['cid']})",
        f"- Mode: {export['enclave']['flags']}; {export['enclave']['cpu_count']} vCPUs, "
        f"{export['enclave']['memory_mib']} MiB",
        f"- Gateway enabled: {str(export['environment']['gateway_enabled']).lower()}",
        f"- EIF image digest: `{export['image_digest']}`",
        f"- Measured calls: {summary['sampleCount']}",
        f"- Successful encrypted round trips: {summary['allEncryptionRoundTripsVerified']}",
        "",
        "## Annotated call latency",
        "",
        "| Mean (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |",
        "|---:|---:|---:|---:|---:|---:|",
        "| "
        + " | ".join(
            f"{summary[key] / 1_000_000:.3f}"
            for key in (
                "meanAnnotatedCallNanos",
                "p50AnnotatedCallNanos",
                "p90AnnotatedCallNanos",
                "p95AnnotatedCallNanos",
                "p99AnnotatedCallNanos",
                "maxAnnotatedCallNanos",
            )
        )
        + " |",
        "",
        "The annotated-call interval includes the `@ConfidentialAI` runtime verifier, "
        "fresh NSM attestation/KMS key release, and AES-GCM secure-context encryption "
        "and authenticated decryption. Component timings are in `samples.csv`. "
        "Each synthetic payload was recovered exactly from its encrypted context. "
        "Payload values and ciphertext are intentionally excluded from the export.",
        "",
        "Raw, chart-ready per-call measurements: [samples.csv](./samples.csv). "
        "Full metadata and measurements: [summary.json](./summary.json).",
        "",
    ]
    report_path.write_text("\n".join(report), encoding="utf-8")
    return samples_path, summary_path, report_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--enclave-name", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--image-digest", required=True)
    parser.add_argument("--pcr0", required=True)
    parser.add_argument("--pcr1", required=True)
    parser.add_argument("--pcr2", required=True)
    parser.add_argument("--port", type=int, default=8002)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        enclave = describe_enclave(args.enclave_name)
        document = fetch_results(enclave["EnclaveCID"], args.port, args.timeout)
        export = build_export(
            document,
            enclave,
            args.region,
            args.image_digest,
            {"PCR0": args.pcr0, "PCR1": args.pcr1, "PCR2": args.pcr2},
        )
        paths = write_exports(export, args.output_dir)
    except (ExportError, KeyError, OSError, ValueError) as error:
        print(f"Benchmark export failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(export["summary"], indent=2))
    for path in paths:
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
