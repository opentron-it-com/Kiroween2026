import csv
import json
import tempfile
import unittest
from pathlib import Path

from deploy.aws_nitro_confidential_benchmark_export import (
    ExportError,
    build_export,
    validate_document,
    write_exports,
)


def benchmark_document():
    return {
        "benchmark": "aws-nitro-confidential-annotation-aes-gcm-roundtrip",
        "generatedAtUtc": "2026-10-23T10:00:00Z",
        "methodology": {
            "warmupRequestsExcluded": 1,
            "measuredRequests": 2,
            "gatewayDisabled": True,
            "everyAnnotatedCallPassedAesGcmRoundTrip": True,
        },
        "summary": {
            "sampleCount": 2,
            "meanAnnotatedCallNanos": 1500,
            "p50AnnotatedCallNanos": 1000,
            "p90AnnotatedCallNanos": 2000,
            "p95AnnotatedCallNanos": 2000,
            "p99AnnotatedCallNanos": 2000,
            "maxAnnotatedCallNanos": 2000,
            "allEncryptionRoundTripsVerified": True,
        },
        "samples": [
            {
                "sample": index,
                "timestampUtc": f"2026-10-23T10:00:0{index}Z",
                "plaintextBytes": 60,
                "encryptedContextBytes": 140,
                "annotatedCallNanos": index * 1000,
                "aesGcmEncryptNanos": 300,
                "aesGcmDecryptNanos": 200,
                "roundTripVerified": True,
            }
            for index in (1, 2)
        ],
    }


class AwsNitroConfidentialBenchmarkExportTest(unittest.TestCase):
    def test_rejects_missing_or_failed_encryption_samples(self):
        document = benchmark_document()
        document["samples"][0]["roundTripVerified"] = False
        with self.assertRaises(ExportError):
            validate_document(document)

    def test_requires_gateway_disabled_and_aes_roundtrip_evidence(self):
        document = benchmark_document()
        document["methodology"]["gatewayDisabled"] = False
        with self.assertRaises(ExportError):
            validate_document(document)

    def test_exports_raw_csv_json_and_presentation_report(self):
        document = benchmark_document()
        validate_document(document)
        export = build_export(
            document,
            {
                "EnclaveName": "cjae-benchmark",
                "EnclaveCID": 20,
                "State": "RUNNING",
                "Flags": "NONE",
                "NumberOfCPUs": 2,
                "MemoryMiB": 4096,
            },
            "eu-north-1",
            "sha256:" + "a" * 64,
            {"PCR0": "a" * 96, "PCR1": "b" * 96, "PCR2": "c" * 96},
        )
        with tempfile.TemporaryDirectory() as directory:
            csv_path, json_path, report_path = write_exports(export, Path(directory))
            with csv_path.open(encoding="utf-8", newline="") as source:
                rows = list(csv.DictReader(source))
            summary = json.loads(json_path.read_text(encoding="utf-8"))
            report = report_path.read_text(encoding="utf-8")

        self.assertEqual(2, len(rows))
        self.assertEqual("0.001000", rows[0]["annotated_call_ms"])
        self.assertEqual("0.000300", rows[0]["aes_gcm_encrypt_ms"])
        self.assertTrue(rows[0]["round_trip_verified"])
        self.assertEqual("eu-north-1", summary["region"])
        self.assertFalse(summary["environment"]["gateway_enabled"])
        self.assertIn("samples.csv", report)
        self.assertIn("P95", report)


if __name__ == "__main__":
    unittest.main()
