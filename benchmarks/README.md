# OpenTron Benchmark Suite

This directory contains legacy virtual-thread/I/O benchmarks and the
separately gated AWS Nitro confidential RAG experiment. Results from one test
must not be generalized to another; no performance advantage is assumed.

## Overview

The historical task benchmark files in this directory are not evidence for
confidential RAG performance, Opaque, Apache Spark, or production customer
data. Use the live AWS Nitro export described below for that experiment.

## Reproducible I/O-concurrency comparison

`docker-compose.repro.yml` and `reproducible_benchmark.py` run the same
blocking-I/O task workload against OpenTron's Java virtual-thread benchmark
endpoint and a FastAPI/Celery/Redis baseline. Each stack is limited to 2 vCPU
and 2 GiB; the Python limit is divided between its API, three workers, and
Redis. The harness measures end-to-end batch p99, completed task throughput,
and container-group memory. It does not call an LLM or exercise full agent
reasoning, storage, or connector workflows.

Run from the repository root:

```bash
docker compose -f benchmarks/docker-compose.repro.yml -p opentron-reprobench up -d --build
python benchmarks/reproducible_benchmark.py
docker compose -f benchmarks/docker-compose.repro.yml -p opentron-reprobench down
```

The runner waits for both health endpoints and saves raw latency samples to
`benchmarks/reproducible_benchmark_results.json` and a summary to
`benchmarks/REPRODUCIBLE_BENCHMARK_REPORT.md`. The Python image pins its full
resolved dependency set, and image versions are specified in the Compose and
Docker files. See the [measured report](./REPRODUCIBLE_BENCHMARK_REPORT.md)
and [raw results](./reproducible_benchmark_results.json). Re-run on the target
hardware before making capacity or cost claims; the report records its host,
container limits, success counts, and workload assumptions.

To export the current measurements first, followed by the clearly labeled
historical results, regenerate the executive-summary PDF:

```bash
python -m pip install -r benchmarks/requirements-pdf.txt
python benchmarks/export_executive_summary.py
```

The PDF is written to
`benchmarks/OpenTron_Benchmark_Executive_Summary.pdf`, the file linked from the
repository README. Historical data is included for context only because its
recorded workload and environment are incomplete and its report conflicts with
the raw JSON. It also summarizes implemented governance controls and their
limits without asserting certification or OPAQUE feature parity.

## Components

### 1. Java Benchmarks (JMH)

Located in: `java/opentron-java/backend/src/test/java/org/opentron/backend/benchmark/`

- **VirtualThreadConcurrencyBenchmark**: Tests throughput and memory at concurrency levels 100→10K
- **LatencyPercentileBenchmark**: Measures p50/p95/p99 latencies under load

Run JMH benchmarks standalone:
```bash
cd java/opentron-java/backend
mvn clean package -DskipTests
java -jar target/benchmarks.jar
```

### 2. Python Baseline

Located in: `benchmarks/python_baseline/`

- **app.py**: FastAPI server + Celery task definitions
- **Dockerfile**: Containerized Python runtime
- Requires: Redis, 3+ Celery workers for concurrency

### 3. Load Generator

**load_generator.py**: Async load tester that:
- Submits concurrent requests to both OpenTron and Python
- Measures latencies and throughput
- Generates comparison report with percentile analysis

### 4. Monitoring Stack

- **Prometheus**: Metrics collection from Actuator endpoints
- **Grafana**: Dashboards for real-time visualization
- **Redis Exporter**: Task queue metrics

### 5. Docker Compose

**docker-compose.yml**: Complete stack:
- OpenTron backend (Java 21)
- Python API + 3 Celery workers
- Redis (task broker)
- PostgreSQL (state persistence)
- Prometheus + Grafana (monitoring)
- Load generator (automated testing)

## Quick Start

### 1. Prerequisites

```bash
# Ensure Docker and Docker Compose are installed
docker --version
docker-compose --version

# Java 21+ for JMH benchmarks (optional, results included)
java -version
```

### 2. Run Full Benchmark Suite

```bash
cd benchmarks

# Start all services (builds on first run, ~5 minutes)
docker-compose up --pull always

# Wait for services to be healthy (~30 seconds)
# Load generator automatically runs after all services are ready
```

### 3. Monitor in Real-time

**Grafana Dashboard:**
- URL: http://localhost:3000
- Username: `admin`
- Password: `admin`

**Prometheus Queries:**
- URL: http://localhost:9090
- Browse metrics: `http_requests_total`, `jvm_memory_used_bytes`, etc.

### 4. View Results

After load generator completes:

```bash
# Results are saved to
cat benchmark_results.json

# Generate markdown report
python3 analyze_results.py benchmark_results.json

# Read report
cat BENCHMARK_REPORT.md
```

## Running Individual Tests

### JMH Benchmarks (Standalone)

```bash
# Build benchmarks
cd java/opentron-java/backend
mvn clean package -DskipTests

# Run all JMH benchmarks
java -jar target/benchmarks.jar

# Run specific benchmark
java -jar target/benchmarks.jar VirtualThreadConcurrencyBenchmark

# Run with custom JMH options
java -jar target/benchmarks.jar \
  -w 2 \                          # warmup iterations
  -i 5 \                          # measurement iterations
  -f 2 \                          # forks
  -t 1 \                          # threads
  -r 10s                          # time per iteration
```

### Python Baseline (Standalone)

```bash
# Start Redis locally
docker run -d -p 6379:6379 redis:7

# Terminal 1: Start FastAPI server
cd benchmarks/python_baseline
pip install -r requirements.txt
python -m uvicorn app:app --host 0.0.0.0 --port 8001

# Terminal 2: Start Celery worker
celery -A app.celery_app worker -l info -c 10

# Terminal 3: Load test
cd benchmarks
python3 load_generator.py
```

### Load Generator (Custom Config)

Modify concurrency levels and task counts in `load_generator.py`:

```python
configs = [
    {"concurrency": 10, "total_tasks": 100},
    {"concurrency": 100, "total_tasks": 1000},
    {"concurrency": 1000, "total_tasks": 5000},
    # ... add more
]
```

## Benchmark Methodology

### Test Scenarios

1. **Throughput Under Load**
   - Submits N concurrent tasks
   - Measures total time to completion
   - Calculates tasks/second

2. **Latency Percentiles**
   - Measures p50, p95, p99 latencies
   - Isolates tail latency performance

3. **Memory Footprint**
   - Captures heap usage before/after concurrency ramp-up
   - Calculates bytes-per-task

4. **I/O Efficiency**
   - Each task includes 500ms network latency (simulated LLM API call)
   - Measures how effectively each platform parks threads during I/O

### LLM API Simulation

Both platforms simulate the same workload:
```
Task = 500ms network wait (LLM API) + minimal CPU work
```

This realistic workload highlights:
- **Virtual threads**: Automatically park → free OS thread for other work
- **Python threads**: Block OS threads → require more workers

### Test Duration

- Warmup: 1-2 iterations (JVM JIT compilation)
- Measurement: 3 iterations × 10-30 seconds per config
- Full suite: ~10-15 minutes

## AWS Nitro Confidential RAG Benchmark

This is the empirical comparison for the confidential JVM path. It processes
1,000,000 deterministically generated records per sample: tokenization,
16-dimensional hash embeddings, cosine top-k retrieval, and prompt packaging.
The Java path stores its retained embedding index in a zeroizing Panama
`MemorySegment`; it still creates temporary Java strings and vectors, so this
is not a claim that the complete text pipeline is allocation-free. The Python
baseline is a local `pickle` serialization/deserialization proxy, not Opaque
and not an Apache Spark deployment.

Both paths run sequentially inside the same AWS Nitro Enclave with the same
CPU/memory assignment. The enclosing `@ConfidentialAI` operation performs
fresh runtime verification once; pipeline timings exclude that shared
verification time. Each measured prompt pair is sealed and reopened with
AES-256-GCM, and Java/Python retrieval digests must match before results can be
exported. The external gateway and Nitro debug mode remain disabled.

Build the worker image with these flags in addition to the required AWS Nitro
KMS ciphertext and profile settings:

```text
AWS_NITRO_VERIFY_ON_STARTUP=true
AWS_NITRO_HEALTH_VSOCK_ENABLED=true
AWS_NITRO_RAG_BENCHMARK_ENABLED=true
AWS_NITRO_DIAGNOSTICS_ENABLED=true
CONFIDENTIAL_GATEWAY_ENABLED=false
```

After building a new EIF, install its exact PCR measurements into the narrowly
scoped KMS and worker-role policies, launch it with 2 CPUs and 4096 MiB in
normal mode, and confirm the parent-only VSOCK status is ready. On the Nitro
parent, export only a successful matching run:

```bash
sudo python3 deploy/aws_nitro_rag_benchmark_report.py \
  --enclave-name cjae-worker-confidential-benchmark \
  --region eu-north-1 \
  --instance-type c5.2xlarge \
  --eif /opt/cjae/worker-confidential-rag.eif \
  --output-dir /opt/cjae/rag-benchmark-export
```

The exporter validates fresh startup attestation, the disabled gateway,
matching top-k results, per-sample CPU/RSS/timing, and verified prompt
encryption before writing `samples.csv`, `summary.json`,
`executive_summary.md`, and `academic_presentation.pdf`. The 3x latency and
5x throughput figures are targets only; the report computes the ratios from
captured samples and states whether each target was met. RSS is process
high-water memory (whole Java worker JVM versus Python child process), not
whole-instance memory; that scope distinction is stated in the report.

Run the local implementation checks with:

```powershell
python -m unittest deploy.tests.test_aws_nitro_rag_proxy deploy.tests.test_aws_nitro_rag_benchmark_report
mvn -f java/opentron-java/confidential-worker/pom.xml test
```

The existing `benchmark_results.json`, `BENCHMARK_REPORT.md`, and
`OpenTron_Benchmark_Executive_Summary.pdf` are historical I/O/task artifacts,
not inputs or results for this confidential RAG experiment.

## Troubleshooting

### Docker Compose Won't Start

```bash
# Check disk space
docker system df

# Clean up unused images/volumes
docker system prune -a

# Rebuild from scratch
docker-compose down -v
docker-compose up --build
```

### Load Generator Fails to Connect

```bash
# Verify services are running
docker-compose ps

# Check service logs
docker-compose logs opentron-backend
docker-compose logs python-api

# Wait for services to be fully healthy (30+ seconds)
```

### Out of Memory Errors

**For Python workers:**
```bash
# Increase available memory in docker-compose.yml
services:
  python-worker-1:
    deploy:
      resources:
        limits:
          memory: 2G
```

**For OpenTron:**
```bash
# Increase JVM heap in Dockerfile
ENTRYPOINT [ "java", "-Xmx2G", ... ]
```

## Customization

### Adjusting Concurrency

Edit `load_generator.py`:
```python
configs = [
    {"concurrency": 100, "total_tasks": 1000},
    {"concurrency": 5000, "total_tasks": 25000},  # More extreme
]
```

### Adjusting LLM Latency

Edit `load_generator.py`:
```python
result = await runner.run_benchmark(
    concurrency=1000,
    total_tasks=5000,
    task_delay_ms=1000  # 1 second per task
)
```

### Custom Prometheus Metrics

Edit `monitoring/prometheus.yml`:
```yaml
scrape_configs:
  - job_name: 'my-custom-app'
    static_configs:
      - targets: ['my-host:9090']
```

## Performance Tuning

### OpenTron

**Virtual Thread Parallelism** (in Dockerfile):
```bash
-Djdk.virtualThreadScheduler.parallelism=4  # Number of OS threads backing virtual threads
```

**GC Tuning:**
```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+ParallelRefProcEnabled
```

### Python Workers

**Celery Concurrency** (in docker-compose.yml):
```yaml
celery -A app.celery_app worker -c 20  # 20 concurrent tasks per worker
```

**Prefetch Multiplier:**
```python
celery_app.conf.worker_prefetch_multiplier = 1  # Conservative; increase to 4 for throughput
```

## Files Structure

```
benchmarks/
├── docker-compose.yml              # Complete test stack
├── application.yml                 # Spring Boot config
├── load_generator.py               # Async load tester
├── analyze_results.py              # Report generator
├── benchmark_results.json          # Results (generated)
├── BENCHMARK_REPORT.md             # Report (generated)
│
├── python_baseline/
│   ├── app.py                      # FastAPI + Celery
│   ├── requirements.txt            # Dependencies
│   └── Dockerfile                  # Container image
│
└── monitoring/
    ├── prometheus.yml              # Metrics config
    └── grafana/
        ├── dashboards/
        └── datasources/
```

## References

- [Java 21 Virtual Threads](https://openjdk.org/projects/loom/)
- [Spring Boot Actuator](https://spring.io/guides/gs/actuator-service/)
- [Celery Distributed Task Queue](https://docs.celeryproject.io/)
- [JMH Benchmarking](https://openjdk.org/projects/code-tools/jmh/)
- [OpenTron Architecture](https://opentron.it.com/)

## License

This benchmark suite is provided as-is for evaluation and research purposes.
OpenTron source code is subject to the repository's license terms.

## Questions?

See the main OpenTron repository: https://github.com/open-tron-ai/OpenTron
