# Reproducible I/O-Concurrency Benchmark

**Run:** 2026-11-15T18:20:35.091375+00:00
**Host:** 12th Gen Intel(R) Core(TM) i7-12800H; 14 physical / 20 logical CPUs; 31.7 GiB RAM; Docker 29.6.1.

## Results

| Concurrent batch requests | Agent tasks in flight | Java tasks/s | Java p99 batch ms | Java requests succeeded | Python tasks/s | Python p99 batch ms | Python requests succeeded |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10 | 84.51 | 145.65 | 240/240 | 57.11 | 269.53 | 240/240 |
| 8 | 80 | 670.06 | 138.15 | 240/240 | 48.29 | 6917.49 | 240/240 |
| 32 | 320 | 2378.21 | 179.19 | 240/240 | 51.25 | 16998.63 | 240/240 |
| 64 | 640 | 4166.00 | 158.15 | 240/240 | 58.72 | 37979.16 | 240/240 |

## Memory

| Stack | Memory requests succeeded | Idle group MiB | Peak group MiB | Peak increase MiB |
|---|---:|---:|---:|---:|
| opentron-java | 2/2 | 495.6 | 495.8 | 0.2 |
| python-celery | 2/2 | 1120.1 | 1135.3 | 15.2 |

## Method

- Each API request starts 10 independent tasks; each task sleeps 100 ms to model blocking external I/O.
- 240 batch requests per concurrency level; concurrency is the number of simultaneous HTTP batch requests. Throughput counts successfully completed subtasks, not requests.
- Java uses the existing Spring benchmark endpoint, which creates Java 21 virtual threads. Python uses FastAPI, four Uvicorn workers, Celery prefork workers (30 total slots), and Redis.
- Each Python API process serializes result collection through its shared Redis result consumer; Celery task execution remains concurrent across the 30 worker slots.
- Each stack is capped at 2 vCPU and 2 GiB. Python's cap is split across API, worker, and Redis containers; reported Python memory sums those containers. Both stacks run on the same host and are benchmarked sequentially with alternating order by concurrency level.
- p99 is end-to-end HTTP batch-request latency, nearest-rank percentile. The JSON file retains all successful request-latency samples. Success counts are shown; failures are excluded from latency and completed-task throughput.
- Memory is Docker container-group working-set usage. The separate memory stress run executes 2 simultaneous requests of 500 tasks each at 500 ms delay.

## Limitations

This is a controlled I/O-wait benchmark, not a real LLM, tokenization, database, or full multi-agent reasoning benchmark. It isolates task scheduling and queue/runtime overhead. It does not establish a general performance claim for every Python or enterprise stack. Repeat on target hardware and with representative providers, persistence, and agent workflows before using the results for capacity planning.

Raw measurements: `reproducible_benchmark_results.json`.
