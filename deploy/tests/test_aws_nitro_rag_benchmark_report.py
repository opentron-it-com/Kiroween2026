import csv
import sys
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from deploy.aws_nitro_confidential_benchmark_export import ExportError
from deploy.aws_nitro_rag_benchmark_report import (
    build_export,
    main,
    validate_document,
    write_exports,
)


def sample(index, elapsed, platform_name):
    common = {
        "sample": index,
        "elapsedNanos": elapsed,
        "processCpuNanos": elapsed - 1,
        "processPeakRssKiB": 128_000,
        "promptSha256": "a" * 64,
        "topRecordIds": [420, 472],
    }
    if platform_name == "java":
        return {**common, "nativeIndexBytes": 64_000_000}
    return {
        **common,
        "serializedBytes": 1_000_000,
        "topMatches": [
            {"recordId": 420, "dotProduct": 3, "recordNormSquared": 4},
            {"recordId": 472, "dotProduct": 2, "recordNormSquared": 2},
        ],
    }


def benchmark_document():
    return {
        "benchmark": "aws-nitro-confidential-rag-java-vs-python-serialized-proxy",
        "generatedAtUtc": "2026-10-23T17:00:00Z",
        "methodology": {
            "syntheticRecordsPerSample": 1_000_000,
            "warmupSamplesExcluded": 1,
            "measuredSamples": 2,
            "pythonSerializationBatchSize": 10_000,
            "sameNitroEnclave": True,
            "gatewayDisabled": True,
            "startupAttestationPassed": True,
            "everyAnnotatedCallPassedAesGcmRoundTrip": True,
        },
        "syntheticQuery": "segment-7 region-11 product-42",
        "javaSamples": [
            sample(1, 1_000_000_000, "java"),
            sample(2, 1_100_000_000, "java"),
        ],
        "pythonSamples": [
            sample(1, 6_000_000_000, "python"),
            sample(2, 6_600_000_000, "python"),
        ],
        "totalPythonSerializedBytes": 2_000_000,
        "matchingRetrievalResults": True,
        "everyPromptAesGcmRoundTripVerified": True,
    }


class AwsNitroRagBenchmarkReportTest(unittest.TestCase):
    def test_rejects_failed_attestation_or_mismatched_rag_results(self):
        document = benchmark_document()
        document["methodology"]["startupAttestationPassed"] = False
        with self.assertRaises(ExportError):
            validate_document(document)

        document = benchmark_document()
        document["pythonSamples"][0]["promptSha256"] = "b" * 64
        with self.assertRaises(ExportError):
            validate_document(document)

    def test_exports_raw_samples_summary_and_pdf_presentation(self):
        document = benchmark_document()
        validate_document(document)
        export = build_export(
            document,
            {
                "EnclaveName": "cjae-rag-benchmark",
                "EnclaveCID": 21,
                "State": "RUNNING",
                "Flags": "NONE",
                "NumberOfCPUs": 3,
                "MemoryMiB": 5120,
            },
            "eu-north-1",
            "c5.2xlarge",
            "Intel Xeon",
            {"PCR0": "a" * 96, "PCR1": "b" * 96, "PCR2": "c" * 96},
        )

        with tempfile.TemporaryDirectory() as directory:
            summary_path, csv_path, report_path, pdf_path = write_exports(
                export, Path(directory)
            )
            with csv_path.open(encoding="utf-8", newline="") as source:
                rows = list(csv.DictReader(source))
            markdown = report_path.read_text(encoding="utf-8")
            pdf = pdf_path.read_bytes()
            summary = summary_path.read_text(encoding="utf-8")

        self.assertEqual(4, len(rows))
        self.assertEqual("java", rows[0]["platform"])
        self.assertEqual("python_proxy", rows[2]["platform"])
        self.assertIn("not the Opaque product", markdown)
        self.assertIn("3x latency target was met", markdown)
        self.assertIn('"PCR0"', summary)
        self.assertIn(b"3-vCPU / 5120-MiB enclave", pdf)
        self.assertTrue(pdf.startswith(b"%PDF-1.4"))
        self.assertTrue(pdf.rstrip().endswith(b"%%EOF"))

    def test_uses_described_cid_and_rejects_missing_allocation_metadata(self):
        enclave = {
            "EnclaveName": "cjae-worker-confidential-benchmark",
            "EnclaveCID": 37,
            "State": "RUNNING",
            "Flags": "NONE",
            "NumberOfCPUs": 2,
            "MemoryMiB": 4096,
        }
        with tempfile.TemporaryDirectory() as directory:
            with ExitStack() as stack:
                stack.enter_context(
                    patch.object(
                        sys,
                        "argv",
                        [
                            "aws_nitro_rag_benchmark_report.py",
                            "--instance-type",
                            "c5.2xlarge",
                            "--output-dir",
                            directory,
                        ],
                    )
                )
                stack.enter_context(
                    patch(
                        "deploy.aws_nitro_rag_benchmark_report.describe_enclave",
                        return_value=enclave,
                    )
                )
                fetch_document = stack.enter_context(
                    patch(
                        "deploy.aws_nitro_rag_benchmark_report.fetch_document",
                        return_value=benchmark_document(),
                    )
                )
                stack.enter_context(
                    patch(
                        "deploy.aws_nitro_rag_benchmark_report.collect_cpu_model",
                        return_value="Intel Xeon",
                    )
                )
                stack.enter_context(
                    patch(
                        "deploy.aws_nitro_rag_benchmark_report.collect_image_measurements",
                        return_value={"PCR0": "a" * 96},
                    )
                )
                self.assertEqual(0, main())
            fetch_document.assert_called_once_with(37, 8002, 30)

        invalid_enclave = {**enclave, "MemoryMiB": None}
        with self.assertRaises(ExportError):
            build_export(
                benchmark_document(),
                invalid_enclave,
                "eu-north-1",
                "c5.2xlarge",
                "Intel Xeon",
                {"PCR0": "a" * 96},
            )


if __name__ == "__main__":
    unittest.main()
