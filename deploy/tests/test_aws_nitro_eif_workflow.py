import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from deploy.aws_nitro_eif_workflow import (
    WorkflowError,
    _run,
    build_eif,
    create_key_policy_statement,
    create_role_iam_policy_document,
    extract_measurements,
    measure_eif,
    write_json_private,
)

PCR0 = "a" * 96
PCR1 = "b" * 96
PCR2 = "c" * 96
BUILD_RESULT = {
    "Measurements": {
        "HashAlgorithm": "Sha384",
        "PCR0": PCR0,
        "PCR1": PCR1,
        "PCR2": PCR2,
    }
}


class AwsNitroEifWorkflowTest(unittest.TestCase):
    def test_nitro_cli_runs_with_a_created_artifacts_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            artifacts_path = Path(directory, "cjae-nitro-cli-artifacts")
            completed = subprocess.CompletedProcess(
                ["nitro-cli"], 0, stdout="{}", stderr=""
            )
            with (
                patch.dict(os.environ, {}, clear=True),
                patch(
                    "deploy.aws_nitro_eif_workflow.tempfile.gettempdir",
                    return_value=directory,
                ),
                patch(
                    "deploy.aws_nitro_eif_workflow.subprocess.run",
                    return_value=completed,
                ) as run,
            ):
                self.assertEqual("{}", _run(["nitro-cli", "build-enclave"]))

            self.assertTrue(artifacts_path.is_dir())
            self.assertEqual(
                str(artifacts_path),
                run.call_args.kwargs["env"]["NITRO_CLI_ARTIFACTS"],
            )

    def test_extracts_and_normalizes_sha384_measurements(self):
        result = extract_measurements(BUILD_RESULT)
        self.assertEqual({"PCR0": PCR0, "PCR1": PCR1, "PCR2": PCR2}, result)

    def test_rejects_missing_or_invalid_measurements(self):
        with self.assertRaises(WorkflowError):
            extract_measurements({"Measurements": {"HashAlgorithm": "Sha384"}})
        with self.assertRaises(WorkflowError):
            extract_measurements(
                {"Measurements": {"HashAlgorithm": "Sha384", "PCR0": "not-a-hash"}}
            )

    def test_accepts_nitro_cli_sha384_debug_representation(self):
        result = {
            "Measurements": {
                "HashAlgorithm": "Sha512_48 { .. }",
                "PCR0": PCR0,
            }
        }
        self.assertEqual({"PCR0": PCR0}, extract_measurements(result))

    def test_policy_statement_binds_role_image_and_selected_pcrs(self):
        statement = create_key_policy_statement(
            BUILD_RESULT,
            "arn:aws:iam::123456789012:role/cjae-parent",
            "arn:aws:kms:eu-north-1:123456789012:key/example",
            (0, 2),
        )
        self.assertEqual(
            {
                "kms:RecipientAttestation:ImageSha384": PCR0,
                "kms:RecipientAttestation:PCR0": PCR0,
                "kms:RecipientAttestation:PCR2": PCR2,
            },
            statement["Condition"]["StringEquals"],
        )
        self.assertEqual("kms:Decrypt", statement["Action"])
        self.assertEqual("*", statement["Resource"])

    def test_policy_rejects_missing_pcr_or_invalid_role(self):
        with self.assertRaises(WorkflowError):
            create_key_policy_statement(BUILD_RESULT, "role", "arn:aws:kms:key", (0,))
        with self.assertRaises(WorkflowError):
            create_key_policy_statement(BUILD_RESULT, "arn:aws:iam:role", "arn:aws:kms:key", (8,))

    def test_role_policy_binds_key_image_and_selected_pcrs(self):
        policy = create_role_iam_policy_document(
            BUILD_RESULT,
            "arn:aws:kms:eu-north-1:123456789012:key/example",
            (0, 2),
        )

        self.assertEqual("2012-10-17", policy["Version"])
        self.assertEqual(
            {
                "Sid": "DecryptContextWithAttestedEnclave",
                "Effect": "Allow",
                "Action": "kms:Decrypt",
                "Resource": "arn:aws:kms:eu-north-1:123456789012:key/example",
                "Condition": {
                    "StringEquals": {
                        "kms:RecipientAttestation:ImageSha384": PCR0,
                        "kms:RecipientAttestation:PCR0": PCR0,
                        "kms:RecipientAttestation:PCR2": PCR2,
                    }
                },
            },
            policy["Statement"][0],
        )

    def test_role_policy_rejects_invalid_key_or_unavailable_pcr(self):
        with self.assertRaises(WorkflowError):
            create_role_iam_policy_document(BUILD_RESULT, "arn:aws:kms:key", (0,))
        with self.assertRaises(WorkflowError):
            create_role_iam_policy_document(
                BUILD_RESULT,
                "arn:aws:kms:eu-north-1:123456789012:key/example",
                (8,),
            )

    def test_build_writes_measurements_only_when_eif_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            eif_path = Path(directory, "worker.eif")
            measurements_path = Path(directory, "measurements.json")

            def fake_run(command):
                eif_path.write_bytes(b"EIF")
                self.assertEqual("build-enclave", command[1])
                return json.dumps(BUILD_RESULT)

            measurements = build_eif(
                "cjae-worker:aws",
                eif_path,
                measurements_path,
                run=fake_run,
            )

            self.assertEqual({"PCR0": PCR0, "PCR1": PCR1, "PCR2": PCR2}, measurements)
            self.assertEqual(
                {"HashAlgorithm": "Sha384", "PCR0": PCR0, "PCR1": PCR1, "PCR2": PCR2},
                json.loads(measurements_path.read_text(encoding="utf-8"))["Measurements"],
            )

    def test_build_rejects_cli_success_without_output_eif(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(WorkflowError):
                build_eif(
                    "cjae-worker:aws",
                    Path(directory, "missing.eif"),
                    Path(directory, "measurements.json"),
                    run=lambda _command: json.dumps(BUILD_RESULT),
                )

    def test_measure_eif_reads_and_saves_only_validated_measurements(self):
        with tempfile.TemporaryDirectory() as directory:
            eif_path = Path(directory, "worker.eif")
            eif_path.write_bytes(b"EIF")
            measurements_path = Path(directory, "measurements.json")
            result = {
                "ImageName": "cjae-worker",
                "ImageVersion": "sha256:build-id",
                **BUILD_RESULT,
            }

            measurements = measure_eif(
                eif_path,
                measurements_path,
                run=lambda command: json.dumps(result),
            )

            self.assertEqual({"PCR0": PCR0, "PCR1": PCR1, "PCR2": PCR2}, measurements)
            saved = json.loads(measurements_path.read_text(encoding="utf-8"))
            self.assertEqual("cjae-worker", saved["ImageName"])
            self.assertEqual("Sha384", saved["Measurements"]["HashAlgorithm"])

    @patch("deploy.aws_nitro_eif_workflow.os.chmod")
    def test_json_output_is_written_with_private_mode(self, chmod):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "policy.json")
            write_json_private(path, {"Effect": "Allow"})
            self.assertEqual({"Effect": "Allow"}, json.loads(path.read_text(encoding="utf-8")))
            self.assertTrue(chmod.called)


if __name__ == "__main__":
    unittest.main()
