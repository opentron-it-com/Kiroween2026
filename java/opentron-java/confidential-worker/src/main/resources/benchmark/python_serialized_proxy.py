#!/usr/bin/env python3
"""Synthetic Python serialization proxy; intentionally not Apache Spark or Opaque."""

from __future__ import annotations

import argparse
import array
import hashlib
import heapq
import json
import math
import pickle
import time
from pathlib import Path

DIMENSIONS = 16
MAX_RECORDS = 2_000_000


def synthetic_record(record_id: int) -> str:
    return (
        f"customer-{record_id} "
        f"segment-{record_id % 31} "
        f"region-{(record_id * 17 + 5) % 23} "
        f"product-{(record_id * 7 + 3) % 101} "
        f"tier-{(record_id * 13 + 1) % 7}"
    )


def fnv1a(value: str) -> int:
    result = 0x811C9DC5
    for byte in value.encode("utf-8"):
        result = ((result ^ byte) * 0x01000193) & 0xFFFFFFFF
    return result if result < 0x80000000 else result - 0x100000000


def embed(tokens: list[str], first_token: int = 1) -> list[int]:
    vector = [0] * DIMENSIONS
    for token in tokens[first_token:]:
        hashed = fnv1a(token)
        vector[hashed & (DIMENSIONS - 1)] += -1 if hashed < 0 else 1
    return vector


def run_pipeline(
    record_count: int,
    query: str,
    top_k: int,
    batch_size: int,
) -> tuple[list[int], str, int, str, list[dict[str, int]]]:
    index = array.array("i", [0]) * (record_count * DIMENSIONS)
    serialized_bytes = 0

    for start in range(0, record_count, batch_size):
        end = min(start + batch_size, record_count)
        source_records = [synthetic_record(record_id) for record_id in range(start, end)]
        serialized_records = pickle.dumps(source_records, protocol=pickle.HIGHEST_PROTOCOL)
        serialized_bytes += len(serialized_records)
        decoded_records = pickle.loads(serialized_records)
        for offset, record in enumerate(decoded_records, start=start):
            vector = embed(record.split(" "))
            destination = offset * DIMENSIONS
            for dimension, value in enumerate(vector):
                index[destination + dimension] = value

    query_vector = embed(query.split(" "), 0)
    query_norm = sum(value * value for value in query_vector)
    best: list[tuple[float, int, int, int, int]] = []
    for record_id in range(record_count):
        offset = record_id * DIMENSIONS
        dot_product = 0
        record_norm = 0
        for dimension, query_value in enumerate(query_vector):
            value = index[offset + dimension]
            dot_product += value * query_value
            record_norm += value * value
        if record_norm == 0 or query_norm == 0:
            continue
        candidate = (
            dot_product / math.sqrt(record_norm * query_norm),
            -record_id,
            record_id,
            dot_product,
            record_norm,
        )
        if len(best) < top_k:
            heapq.heappush(best, candidate)
        elif candidate > best[0]:
            heapq.heapreplace(best, candidate)

    matches = sorted(best, reverse=True)
    prompt = f"query={query}\n" + "".join(
        f"{record_id},{dot_product},{record_norm}\n"
        for _, _, record_id, dot_product, record_norm in matches
    )
    prompt_digest = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
    hit_details = [
        {
            "recordId": match[2],
            "dotProduct": match[3],
            "recordNormSquared": match[4],
        }
        for match in matches
    ]
    return [match[2] for match in matches], prompt_digest, serialized_bytes, prompt, hit_details


def peak_rss_kib() -> int:
    status = Path("/proc/self/status").read_text(encoding="ascii")
    for line in status.splitlines():
        if line.startswith("VmHWM:"):
            return int(line.split()[1])
    raise RuntimeError("Python process peak RSS metric is unavailable.")


def measured_sample(
    sample: int, record_count: int, query: str, top_k: int, batch_size: int
) -> dict[str, object]:
    cpu_started = time.process_time_ns()
    wall_started = time.monotonic_ns()
    record_ids, prompt_digest, serialized_bytes, prompt, hit_details = run_pipeline(
        record_count, query, top_k, batch_size
    )
    return {
        "sample": sample,
        "elapsedNanos": time.monotonic_ns() - wall_started,
        "processCpuNanos": time.process_time_ns() - cpu_started,
        "processPeakRssKiB": peak_rss_kib(),
        "serializedBytes": serialized_bytes,
        "promptSha256": prompt_digest,
        "topRecordIds": record_ids,
        "topMatches": hit_details,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--record-count", type=int, required=True)
    parser.add_argument("--warmups", type=int, required=True)
    parser.add_argument("--samples", type=int, required=True)
    parser.add_argument("--batch-size", type=int, required=True)
    parser.add_argument("--query", required=True)
    parser.add_argument("--top-k", type=int, required=True)
    args = parser.parse_args()
    if not 1 <= args.record_count <= MAX_RECORDS:
        parser.error(f"--record-count must be between 1 and {MAX_RECORDS}")
    if not 1 <= args.warmups <= 10 or not 1 <= args.samples <= 10:
        parser.error("--warmups and --samples must be between 1 and 10")
    if not 1 <= args.batch_size <= 100_000:
        parser.error("--batch-size must be between 1 and 100000")
    if not 1 <= args.top_k <= min(100, args.record_count):
        parser.error("--top-k is outside the supported range")
    query_tokens = args.query.split(" ")
    if (
        len(query_tokens) != 3
        or not query_tokens[0].startswith("segment-")
        or not query_tokens[1].startswith("region-")
        or not query_tokens[2].startswith("product-")
    ):
        parser.error("--query must use segment-N region-N product-N synthetic fields")
    try:
        segment_id, region_id, product_id = (
            int(token.split("-", 1)[1]) for token in query_tokens
        )
    except ValueError:
        parser.error("--query must contain numeric synthetic field values")
    if not (0 <= segment_id < 31 and 0 <= region_id < 23 and 0 <= product_id < 101):
        parser.error("--query synthetic field values are outside the supported ranges")
    return args


def main() -> None:
    args = parse_args()
    for _ in range(args.warmups):
        run_pipeline(args.record_count, args.query, args.top_k, args.batch_size)

    samples = [
        measured_sample(
            index + 1,
            args.record_count,
            args.query,
            args.top_k,
            args.batch_size,
        )
        for index in range(args.samples)
    ]
    total_serialized = sum(int(sample["serializedBytes"]) for sample in samples)
    print(
        json.dumps(
            {
                "benchmark": "python-serialized-proxy",
                "recordCount": args.record_count,
                "warmupSamplesExcluded": args.warmups,
                "measuredSamples": args.samples,
                "serializationBatchSize": args.batch_size,
                "totalSerializedBytes": total_serialized,
                "samples": samples,
            },
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
