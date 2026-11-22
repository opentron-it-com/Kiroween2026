#!/usr/bin/env python3
from __future__ import annotations

"""Build, measure, and launch a CJAE Nitro Enclave image without changing AWS policies."""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

SHA384_HEX = re.compile(r"^[0-9a-fA-F]{96}$")
ROLE_ARN = re.compile(r"^arn:[a-z0-9-]+:iam::[0-9]{12}:role/.+$")
KEY_ARN = re.compile(r"^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/.+$")
DEFAULT_PCR_INDICES = (0, 1, 2)
SHA384_ALGORITHMS = {"Sha384", "Sha512_48 { .. }"}


class WorkflowError(RuntimeError):
    """An AWS Nitro CLI operation or its output was invalid."""


def _run(command: list[str]) -> str:
    environment = os.environ.copy()
    artifacts_path = environment.get("NITRO_CLI_ARTIFACTS")
    if not artifacts_path:
        artifacts_path = str(Path(tempfile.gettempdir()) / "cjae-nitro-cli-artifacts")
        environment["NITRO_CLI_ARTIFACTS"] = artifacts_path
    Path(artifacts_path).mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            check=False,
            text=True,
            timeout=900,
            env=environment,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise WorkflowError(f"Unable to run {command[0]}: {error}") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise WorkflowError(
            f"{command[0]} failed with exit status {result.returncode}: {detail}"
        )
    return result.stdout


def _parse_json_output(output: str, command_name: str) -> Any:
    try:
        value = json.loads(output)
    except json.JSONDecodeError as error:
        raise WorkflowError(f"{command_name} did not return valid JSON.") from error
    return value


def extract_measurements(build_result: dict[str, Any]) -> dict[str, str]:
    measurements = build_result.get("Measurements")
    if not isinstance(measurements, dict):
        raise WorkflowError("nitro-cli output is missing the Measurements object.")
    algorithm = measurements.get("HashAlgorithm")
    if algorithm not in SHA384_ALGORITHMS:
        raise WorkflowError("Nitro EIF measurements must use SHA-384.")

    validated: dict[str, str] = {}
    for index in range(9):
        value = measurements.get(f"PCR{index}")
        if value is not None:
            if not isinstance(value, str) or not SHA384_HEX.fullmatch(value):
                raise WorkflowError(f"Nitro CLI returned an invalid PCR{index} value.")
            validated[f"PCR{index}"] = value.lower()
    if "PCR0" not in validated:
        raise WorkflowError("Nitro CLI output is missing the required PCR0 measurement.")
    return validated


def write_json_private(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    try:
        os.chmod(temporary_name, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(data, output, indent=2, sort_keys=True)
            output.write("\n")
        os.replace(temporary_name, path)
        os.chmod(path, 0o600)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def build_eif(
    docker_uri: str,
    output_file: Path,
    measurements_file: Path,
    run: Callable[[list[str]], str] = _run,
) -> dict[str, str]:
    if not docker_uri.strip():
        raise WorkflowError("A local Docker image URI is required.")
    output_file.parent.mkdir(parents=True, exist_ok=True)
    result = _parse_json_output(
        run(
            [
                "nitro-cli",
                "build-enclave",
                "--docker-uri",
                docker_uri,
                "--output-file",
                str(output_file),
            ]
        ),
        "nitro-cli build-enclave",
    )
    if not isinstance(result, dict):
        raise WorkflowError("nitro-cli build-enclave returned an unexpected JSON value.")
    measurements = extract_measurements(result)
    if not output_file.is_file() or output_file.stat().st_size == 0:
        raise WorkflowError("nitro-cli reported success but did not produce the EIF file.")
    write_json_private(
        measurements_file,
        {
            "DockerImage": docker_uri,
            "Measurements": {"HashAlgorithm": "Sha384", **measurements},
        },
    )
    return measurements


def measure_eif(
    eif_file: Path,
    measurements_file: Path,
    run: Callable[[list[str]], str] = _run,
) -> dict[str, str]:
    if not eif_file.is_file() or eif_file.stat().st_size == 0:
        raise WorkflowError(f"EIF does not exist or is empty: {eif_file}")
    result = _parse_json_output(
        run(["nitro-cli", "describe-eif", "--eif-path", str(eif_file)]),
        "nitro-cli describe-eif",
    )
    if not isinstance(result, dict):
        raise WorkflowError("nitro-cli describe-eif returned an unexpected JSON value.")
    measurements = extract_measurements(result)
    write_json_private(
        measurements_file,
        {
            "EIF": str(eif_file),
            "ImageName": result.get("ImageName"),
            "ImageVersion": result.get("ImageVersion"),
            "Measurements": {"HashAlgorithm": "Sha384", **measurements},
        },
    )
    return measurements


def create_key_policy_statement(
    measurements_document: dict[str, Any],
    role_arn: str,
    key_arn: str,
    pcr_indices: tuple[int, ...] = DEFAULT_PCR_INDICES,
) -> dict[str, Any]:
    if not ROLE_ARN.fullmatch(role_arn) or not KEY_ARN.fullmatch(key_arn):
        raise WorkflowError("A valid IAM role ARN and KMS key ARN are required.")
    conditions = _create_attestation_conditions(measurements_document, pcr_indices)

    return {
        "Sid": "AllowCJAEDecryptForMeasuredNitroEnclave",
        "Effect": "Allow",
        "Principal": {"AWS": role_arn},
        "Action": "kms:Decrypt",
        "Resource": "*",
        "Condition": {"StringEquals": conditions},
    }


def create_role_iam_policy_document(
    measurements_document: dict[str, Any],
    key_arn: str,
    pcr_indices: tuple[int, ...] = DEFAULT_PCR_INDICES,
) -> dict[str, Any]:
    if not KEY_ARN.fullmatch(key_arn):
        raise WorkflowError("A valid KMS key ARN is required.")
    conditions = _create_attestation_conditions(measurements_document, pcr_indices)

    return {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Sid": "DecryptContextWithAttestedEnclave",
                "Effect": "Allow",
                "Action": "kms:Decrypt",
                "Resource": key_arn,
                "Condition": {"StringEquals": conditions},
            }
        ],
    }


def _create_attestation_conditions(
    measurements_document: dict[str, Any],
    pcr_indices: tuple[int, ...],
) -> dict[str, str]:
    measurements = extract_measurements(measurements_document)
    if not pcr_indices or len(set(pcr_indices)) != len(pcr_indices):
        raise WorkflowError("Select one or more unique PCR indices.")
    for index in pcr_indices:
        if index < 0 or index > 8 or f"PCR{index}" not in measurements:
            raise WorkflowError(f"PCR{index} is not available in the measurement file.")

    pcr0 = measurements["PCR0"]
    conditions = {"kms:RecipientAttestation:ImageSha384": pcr0}
    for index in sorted(pcr_indices):
        conditions[f"kms:RecipientAttestation:PCR{index}"] = measurements[f"PCR{index}"]
    return conditions


def _parse_pcr_indices(value: str) -> tuple[int, ...]:
    try:
        indices = tuple(int(item.strip()) for item in value.split(",") if item.strip())
    except ValueError as error:
        raise argparse.ArgumentTypeError("PCR indices must be comma-separated integers.") from error
    if not indices or any(index < 0 or index > 8 for index in indices):
        raise argparse.ArgumentTypeError("PCR indices must be in the range 0 through 8.")
    if len(set(indices)) != len(indices):
        raise argparse.ArgumentTypeError("PCR indices must be unique.")
    return indices


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise WorkflowError(f"Unable to read valid JSON from {path}.") from error
    if not isinstance(value, dict):
        raise WorkflowError(f"Expected a JSON object in {path}.")
    return value


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    build = commands.add_parser("build-eif", help="Build the EIF and save PCR measurements.")
    build.add_argument("--docker-uri", required=True, help="Local Nitro-compatible Docker image.")
    build.add_argument("--output-file", required=True, type=Path, help="Destination EIF path.")
    build.add_argument(
        "--measurements-file",
        required=True,
        type=Path,
        help="Destination JSON file for measured PCR values.",
    )

    measure = commands.add_parser(
        "measure-eif",
        help="Read and save PCR measurements from an existing EIF.",
    )
    measure.add_argument("--eif-path", required=True, type=Path)
    measure.add_argument("--measurements-file", required=True, type=Path)

    policy = commands.add_parser(
        "write-policy",
        help="Write a reviewable KMS key-policy statement; does not apply it.",
    )
    policy.add_argument("--measurements-file", required=True, type=Path)
    policy.add_argument("--role-arn", required=True, help="Parent EC2 instance role ARN.")
    policy.add_argument("--key-arn", required=True, help="KMS key ARN.")
    policy.add_argument(
        "--pcr-indices",
        type=_parse_pcr_indices,
        default=DEFAULT_PCR_INDICES,
        help="Comma-separated PCR indices to require (default: 0,1,2).",
    )
    policy.add_argument("--output-file", required=True, type=Path)

    role_policy = commands.add_parser(
        "write-role-policy",
        help="Write a reviewable IAM role policy bound to EIF measurements.",
    )
    role_policy.add_argument("--measurements-file", required=True, type=Path)
    role_policy.add_argument("--key-arn", required=True, help="KMS key ARN.")
    role_policy.add_argument(
        "--pcr-indices",
        type=_parse_pcr_indices,
        default=DEFAULT_PCR_INDICES,
        help="Comma-separated PCR indices to require (default: 0,1,2).",
    )
    role_policy.add_argument("--output-file", required=True, type=Path)

    launch = commands.add_parser("run-enclave", help="Launch an EIF without debug mode.")
    launch.add_argument("--eif-path", required=True, type=Path)
    launch.add_argument("--cpu-count", required=True, type=int)
    launch.add_argument("--memory-mib", required=True, type=int)

    commands.add_parser("status", help="Show current Nitro Enclave status.")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        if args.command == "build-eif":
            measurements = build_eif(
                args.docker_uri, args.output_file, args.measurements_file
            )
            print(json.dumps({"EIF": str(args.output_file), "Measurements": measurements}, indent=2))
        elif args.command == "measure-eif":
            measurements = measure_eif(args.eif_path, args.measurements_file)
            print(json.dumps({"EIF": str(args.eif_path), "Measurements": measurements}, indent=2))
        elif args.command == "write-policy":
            document = _read_json(args.measurements_file)
            statement = create_key_policy_statement(
                document, args.role_arn, args.key_arn, args.pcr_indices
            )
            write_json_private(args.output_file, statement)
            print(
                f"Wrote review-only key policy statement for {args.key_arn} "
                f"to {args.output_file}."
            )
        elif args.command == "write-role-policy":
            document = _read_json(args.measurements_file)
            role_policy_document = create_role_iam_policy_document(
                document, args.key_arn, args.pcr_indices
            )
            write_json_private(args.output_file, role_policy_document)
            print(
                f"Wrote review-only IAM role policy for {args.key_arn} "
                f"to {args.output_file}."
            )
        elif args.command == "run-enclave":
            if not args.eif_path.is_file():
                raise WorkflowError(f"EIF does not exist: {args.eif_path}")
            if args.cpu_count < 2 or args.memory_mib < 512:
                raise WorkflowError("Nitro launch requires at least 2 CPUs and 512 MiB.")
            output = _run(
                [
                    "nitro-cli",
                    "run-enclave",
                    "--eif-path",
                    str(args.eif_path),
                    "--cpu-count",
                    str(args.cpu_count),
                    "--memory",
                    str(args.memory_mib),
                ]
            )
            launch_result = _parse_json_output(output, "nitro-cli run-enclave")
            if not isinstance(launch_result, dict):
                raise WorkflowError("nitro-cli run-enclave returned an unexpected JSON value.")
            print(json.dumps(launch_result, indent=2))
        elif args.command == "status":
            output = _run(["nitro-cli", "describe-enclaves"])
            print(json.dumps(_parse_json_output(output, "nitro-cli describe-enclaves"), indent=2))
        return 0
    except WorkflowError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
