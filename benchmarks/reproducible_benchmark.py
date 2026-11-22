#!/usr/bin/env python3
"""Compare the Java virtual-thread and FastAPI/Celery benchmark stacks."""

import argparse
import asyncio
import concurrent.futures
import json
import math
import os
import platform
import re
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent
COMPOSE_FILE = ROOT / "docker-compose.repro.yml"
STACK_SERVICES = {
    "opentron-java": ("opentron-java",),
    "python-celery": ("python-api", "python-worker-1", "python-worker-2", "python-worker-3", "redis"),
}
MEMORY_UNITS = {
    "B": 1,
    "KiB": 1024,
    "MiB": 1024**2,
    "GiB": 1024**3,
    "kB": 1000,
    "MB": 1000**2,
    "GB": 1000**3,
}


def docker_command(project, *args):
    return ["docker", "compose", "-f", str(COMPOSE_FILE), "-p", project, *args]


def run_command(command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()


def parse_memory(value):
    match = re.match(r"\s*([\d.]+)\s*([A-Za-z]+)", value)
    if not match or match.group(2) not in MEMORY_UNITS:
        raise ValueError(f"Unrecognized Docker memory value: {value!r}")
    return float(match.group(1)) * MEMORY_UNITS[match.group(2)]


def stack_memory_bytes(project, stack):
    output = run_command(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.MemUsage}}"]
    )
    services = STACK_SERVICES[stack]
    prefix = f"{project}-"
    values = []
    for line in output.splitlines():
        name, separator, usage = line.partition("|")
        if not separator or not name.startswith(prefix):
            continue
        if any(f"-{service}-" in name for service in services):
            values.append(parse_memory(usage.split("/", 1)[0]))
    if not values:
        raise RuntimeError(f"No running containers found for {project}/{stack}")
    return sum(values)


class MemorySampler:
    def __init__(self, project, stack, interval=0.5):
        self.project = project
        self.stack = stack
        self.interval = interval
        self.samples = []
        self.error = None
        self.stopping = threading.Event()
        self.thread = None
        self.baseline_mib = 0.0

    def start(self):
        baseline = [stack_memory_bytes(self.project, self.stack) for _ in range(3)]
        self.baseline_mib = statistics.mean(baseline) / 1024**2
        self.thread = threading.Thread(target=self._sample_loop, daemon=True)
        self.thread.start()

    def _sample_loop(self):
        while not self.stopping.is_set():
            try:
                self.samples.append(stack_memory_bytes(self.project, self.stack))
            except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
                self.error = str(exc)
                self.stopping.set()
                return
            self.stopping.wait(self.interval)

    def stop(self):
        self.stopping.set()
        if self.thread is not None:
            self.thread.join()
        if self.error:
            raise RuntimeError(f"Docker memory sampling failed: {self.error}")
        values = self.samples or [int(self.baseline_mib * 1024**2)]
        return {
            "idle_mib": round(self.baseline_mib, 1),
            "peak_mib": round(max(values) / 1024**2, 1),
            "peak_delta_mib": round(max(0, max(values) / 1024**2 - self.baseline_mib), 1),
            "sample_count": len(self.samples),
            "sample_interval_seconds": self.interval,
        }


def request_batch(base_url, task_count, delay_ms, timeout_seconds):
    query = urllib.parse.urlencode({"taskCount": task_count, "delayMs": delay_ms})
    request = urllib.request.Request(
        f"{base_url}/v1/agents/stress-test?{query}",
        data=b"",
        headers={"User-Agent": "opentron-repro-benchmark/1"},
        method="POST",
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            payload = json.loads(response.read())
            elapsed_ms = (time.perf_counter() - start) * 1000
            if response.status != 200 or payload.get("status") != "completed":
                return {"ok": False, "latency_ms": elapsed_ms, "error": str(payload)}
            if payload.get("total_tasks") != task_count:
                return {"ok": False, "latency_ms": elapsed_ms, "error": "task count mismatch"}
            return {"ok": True, "latency_ms": elapsed_ms}
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
        return {"ok": False, "latency_ms": (time.perf_counter() - start) * 1000, "error": str(exc)}


def percentile(values, proportion):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(proportion * len(ordered)) - 1)]


async def run_scenario(project, stack, base_url, concurrency, requests, batch_size, delay_ms):
    sampler = MemorySampler(project, stack)
    sampler.start()
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=concurrency)
    loop = asyncio.get_running_loop()
    start = time.perf_counter()
    try:
        futures = [
            loop.run_in_executor(
                pool,
                request_batch,
                base_url,
                batch_size,
                delay_ms,
                300,
            )
            for _ in range(requests)
        ]
        outcomes = await asyncio.gather(*futures, return_exceptions=True)
    finally:
        pool.shutdown(wait=True)
    elapsed = time.perf_counter() - start
    memory = sampler.stop()
    successes = [item for item in outcomes if isinstance(item, dict) and item["ok"]]
    failures = [
        str(item.get("error", "request failed")) if isinstance(item, dict) else str(item)
        for item in outcomes
        if not isinstance(item, dict) or not item["ok"]
    ]
    latencies = [item["latency_ms"] for item in successes]
    completed_tasks = len(successes) * batch_size
    return {
        "stack": stack,
        "concurrency_batches": concurrency,
        "requests": requests,
        "successful_requests": len(successes),
        "batch_size": batch_size,
        "completed_tasks": completed_tasks,
        "failed_requests": len(failures),
        "elapsed_seconds": round(elapsed, 3),
        "throughput_tasks_per_second": round(completed_tasks / elapsed, 2),
        "latency_ms": {
            "p50": round(percentile(latencies, 0.50), 2) if latencies else None,
            "p95": round(percentile(latencies, 0.95), 2) if latencies else None,
            "p99": round(percentile(latencies, 0.99), 2) if latencies else None,
            "samples": latencies,
        },
        "memory": memory,
        "errors": failures[:10],
    }


def wait_for_service(base_url, timeout_seconds=300):
    health_url = f"{base_url}/v1/agents/health"
    deadline = time.monotonic() + timeout_seconds
    last_error = "not ready"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(health_url, timeout=3) as response:
                if response.status == 200:
                    return
                last_error = f"HTTP {response.status}"
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = str(exc)
        time.sleep(1)
    raise TimeoutError(f"Service did not become ready: {health_url}: {last_error}")


def host_metadata(project):
    host = {
        "platform": platform.platform(),
        "processor": platform.processor(),
        "logical_cpus": os.cpu_count(),
        "physical_cores": None,
        "physical_memory_gib": None,
    }
    if os.name == "nt":
        hardware = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                "$cpu=Get-CimInstance Win32_Processor | Select-Object -First 1; "
                "$sys=Get-CimInstance Win32_ComputerSystem; "
                "'{0}|{1}|{2}|{3:N1}' -f $cpu.Name,$cpu.NumberOfCores,"
                "$cpu.NumberOfLogicalProcessors,($sys.TotalPhysicalMemory/1GB)",
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        cpu, cores, logical, memory_gib = hardware.split("|", 3)
        host.update(
            {
                "cpu_model": cpu,
                "physical_cores": int(cores),
                "logical_cpus": int(logical),
                "physical_memory_gib": float(memory_gib),
            }
        )
    else:
        try:
            cpu_info = Path("/proc/cpuinfo").read_text(encoding="utf-8")
            model = re.search(r"^model name\s*:\s*(.+)$", cpu_info, re.MULTILINE)
            if model:
                host["cpu_model"] = model.group(1).strip()
            memory_info = Path("/proc/meminfo").read_text(encoding="utf-8")
            memory = re.search(r"^MemTotal:\s*(\d+)\s*kB$", memory_info, re.MULTILINE)
            if memory:
                host["physical_memory_gib"] = round(int(memory.group(1)) / 1024**2, 1)
        except OSError:
            pass
    docker = run_command(["docker", "info", "--format", "{{.ServerVersion}}|{{.NCPU}}|{{.MemTotal}}"])
    version, cpus, memory_bytes = docker.split("|", 2)
    host["docker"] = {
        "version": version,
        "daemon_cpus": int(cpus),
        "daemon_memory_gib": round(int(memory_bytes) / 1024**3, 1),
    }
    host["compose_images"] = {}
    for service in (
        "opentron-java",
        "python-api",
        "python-worker-1",
        "python-worker-2",
        "python-worker-3",
        "redis",
    ):
        container_id = run_command(docker_command(project, "ps", "-q", service))
        host["compose_images"][service] = run_command(
            ["docker", "inspect", "--format", "{{.Image}}", container_id]
        )
    return host


def render_report(data):
    workload = data["workload"]
    java = {row["concurrency_batches"]: row for row in data["results"] if row["stack"] == "opentron-java"}
    python = {row["concurrency_batches"]: row for row in data["results"] if row["stack"] == "python-celery"}
    lines = [
        "# Reproducible I/O-Concurrency Benchmark",
        "",
        f"**Run:** {data['timestamp_utc']}",
        f"**Host:** {data['host'].get('cpu_model', data['host']['processor'])}; "
        f"{data['host']['physical_cores'] or 'unknown'} physical / "
        f"{data['host']['logical_cpus']} logical CPUs; "
        f"{data['host']['physical_memory_gib'] or 'unknown'} GiB RAM; "
        f"Docker {data['host']['docker']['version']}.",
        "",
        "## Results",
        "",
        "| Concurrent batch requests | Agent tasks in flight | Java tasks/s | Java p99 batch ms | Java requests succeeded | Python tasks/s | Python p99 batch ms | Python requests succeeded |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for concurrency in sorted(java):
        jrow = java[concurrency]
        prow = python[concurrency]
        java_p99 = jrow["latency_ms"]["p99"]
        python_p99 = prow["latency_ms"]["p99"]
        java_p99_text = f"{java_p99:.2f}" if java_p99 is not None else "n/a"
        python_p99_text = f"{python_p99:.2f}" if python_p99 is not None else "n/a"
        lines.append(
            f"| {concurrency} | {concurrency * workload['batch_size']} | "
            f"{jrow['throughput_tasks_per_second']:.2f} | {java_p99_text} | "
            f"{jrow['successful_requests']}/{jrow['requests']} | "
            f"{prow['throughput_tasks_per_second']:.2f} | {python_p99_text} | "
            f"{prow['successful_requests']}/{prow['requests']} |"
        )
    memory = data["memory_stress"]
    lines.extend(
        [
            "",
            "## Memory",
            "",
            "| Stack | Memory requests succeeded | Idle group MiB | Peak group MiB | Peak increase MiB |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for stack in ("opentron-java", "python-celery"):
        result = memory[stack]
        row = result["memory"]
        lines.append(
            f"| {stack} | {result['successful_requests']}/{result['requests']} | "
            f"{row['idle_mib']:.1f} | {row['peak_mib']:.1f} | {row['peak_delta_mib']:.1f} |"
        )
    lines.extend(
        [
            "",
            "## Method",
            "",
            f"- Each API request starts {workload['batch_size']} independent tasks; each task sleeps "
            f"{workload['delay_ms']} ms to model blocking external I/O.",
            f"- {workload['requests_per_level']} batch requests per concurrency level; concurrency is "
            "the number of simultaneous HTTP batch requests. Throughput counts successfully completed "
            "subtasks, not requests.",
            "- Java uses the existing Spring benchmark endpoint, which creates Java 21 virtual threads. "
            "Python uses FastAPI, four Uvicorn workers, Celery prefork workers (30 total slots), and Redis.",
            "- Each Python API process serializes result collection through its shared Redis result consumer; "
            "Celery task execution remains concurrent across the 30 worker slots.",
            "- Each stack is capped at 2 vCPU and 2 GiB. Python's cap is split across API, worker, and Redis "
            "containers; reported Python memory sums those containers. Both stacks run on the same host and "
            "are benchmarked sequentially with alternating order by concurrency level.",
            "- p99 is end-to-end HTTP batch-request latency, nearest-rank percentile. The JSON file retains "
            "all successful request-latency samples. Success counts are shown; failures are excluded from "
            "latency and completed-task throughput.",
            "- Memory is Docker container-group working-set usage. The separate memory stress run executes "
            f"{workload['memory_concurrent_requests']} simultaneous requests of "
            f"{workload['memory_tasks_per_request']} tasks each at {workload['memory_delay_ms']} ms delay.",
            "",
            "## Limitations",
            "",
            "This is a controlled I/O-wait benchmark, not a real LLM, tokenization, database, or full "
            "multi-agent reasoning benchmark. It isolates task scheduling and queue/runtime overhead. "
            "It does not establish a general performance claim for every Python or enterprise stack. "
            "Repeat on target hardware and with representative providers, persistence, and agent workflows "
            "before using the results for capacity planning.",
            "",
            f"Raw measurements: `{Path(data['output_json']).name}`.",
            "",
        ]
    )
    return "\n".join(lines)


async def run(args):
    endpoints = {
        "opentron-java": args.java_url,
        "python-celery": args.python_url,
    }
    for url in endpoints.values():
        wait_for_service(url)

    for stack, url in endpoints.items():
        for _ in range(args.warmup_batches):
            result = request_batch(url, args.batch_size, args.delay_ms, 300)
            if not result["ok"]:
                raise RuntimeError(f"{stack} warmup failed: {result['error']}")

    results = []
    for index, concurrency in enumerate(args.concurrency):
        order = ["opentron-java", "python-celery"]
        if index % 2:
            order.reverse()
        for stack in order:
            result = await run_scenario(
                args.project,
                stack,
                endpoints[stack],
                concurrency,
                args.requests_per_level,
                args.batch_size,
                args.delay_ms,
            )
            results.append(result)
            print(
                f"{stack} concurrency={concurrency}: "
                f"{result['throughput_tasks_per_second']:.2f} tasks/s, "
                f"p99={result['latency_ms']['p99']} ms, "
                f"memory peak={result['memory']['peak_mib']} MiB"
            )

    memory_stress = {}
    for stack in ("python-celery", "opentron-java"):
        memory_stress[stack] = await run_scenario(
            args.project,
            stack,
            endpoints[stack],
            args.memory_concurrent_requests,
            args.memory_concurrent_requests,
            args.memory_tasks_per_request,
            args.memory_delay_ms,
        )
        print(
            f"{stack} memory stress: idle={memory_stress[stack]['memory']['idle_mib']} MiB, "
            f"peak={memory_stress[stack]['memory']['peak_mib']} MiB"
        )

    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "schema_version": 1,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "host": host_metadata(args.project),
        "resource_limits": {
            "java": {"cpus": 2.0, "memory_mib": 2048},
            "python_stack": {"cpus_total": 2.0, "memory_mib_total": 2048},
        },
        "workload": {
            "requests_per_level": args.requests_per_level,
            "batch_size": args.batch_size,
            "delay_ms": args.delay_ms,
            "concurrency_levels": args.concurrency,
            "memory_concurrent_requests": args.memory_concurrent_requests,
            "memory_tasks_per_request": args.memory_tasks_per_request,
            "memory_delay_ms": args.memory_delay_ms,
        },
        "results": results,
        "memory_stress": memory_stress,
        "output_json": str(output),
    }
    output.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    report_path = Path(args.report).resolve()
    report_path.write_text(render_report(data), encoding="utf-8")
    print(f"Results: {output}")
    print(f"Report: {report_path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="opentron-reprobench")
    parser.add_argument("--java-url", default="http://127.0.0.1:18080")
    parser.add_argument("--python-url", default="http://127.0.0.1:18081")
    parser.add_argument("--requests-per-level", type=int, default=240)
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--delay-ms", type=int, default=100)
    parser.add_argument("--concurrency", type=int, nargs="+", default=[1, 8, 32, 64])
    parser.add_argument("--warmup-batches", type=int, default=5)
    parser.add_argument("--memory-concurrent-requests", type=int, default=2)
    parser.add_argument("--memory-tasks-per-request", type=int, default=500)
    parser.add_argument("--memory-delay-ms", type=int, default=500)
    parser.add_argument("--output", default=str(ROOT / "reproducible_benchmark_results.json"))
    parser.add_argument("--report", default=str(ROOT / "REPRODUCIBLE_BENCHMARK_REPORT.md"))
    args = parser.parse_args()
    if args.requests_per_level < 100:
        parser.error("--requests-per-level must be at least 100 for useful p99 samples")
    if any(value < 1 for value in args.concurrency):
        parser.error("concurrency levels must be positive")
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
