"""
FastAPI + Celery + Redis Baseline for OpenTron Benchmark Comparison

This reproduces the traditional Python AI agent stack:
- FastAPI for HTTP API
- Celery for async task queue
- Redis for task broker
- Single process model (scales with worker count)
"""

from fastapi import FastAPI, BackgroundTasks, HTTPException, Query
from celery import Celery, group
import time
import asyncio
import os
import logging
import threading
from typing import List, Dict, Any
from datetime import datetime
import json

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# FastAPI app
app = FastAPI(title="Python AI Agent Baseline")
# Avoid concurrent reads from this process's shared Celery Redis result consumer.
result_wait_lock = threading.Lock()

# Celery configuration
celery_app = Celery(
    "python_baseline",
    broker=os.getenv("CELERY_BROKER_URL", "redis://localhost:6379/0"),
    backend=os.getenv("CELERY_RESULT_BACKEND", "redis://localhost:6379/0"),
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_default_queue="default",
    worker_prefetch_multiplier=1,  # Simulate concurrency constraints
)


@celery_app.task(bind=True, name="agent.query_llm")
def query_llm_task(self, prompt: str, delay_ms: int = 500) -> Dict[str, Any]:
    """
    Simulate LLM query. Each task occupies one Celery worker process slot.
    """
    try:
        logger.info(f"Task {self.request.id} starting LLM query")
        
        # Simulate I/O wait (network call to LLM API)
        time.sleep(delay_ms / 1000.0)
        
        response = {
            "status": "completed",
            "response": f"Response to: {prompt}",
            "task_id": str(self.request.id),
            "timestamp": datetime.utcnow().isoformat(),
        }
        logger.info(f"Task {self.request.id} completed")
        return response
    except Exception as e:
        logger.error(f"Task {self.request.id} failed: {e}")
        return {"status": "error", "error": str(e)}


@app.post("/v1/agents/query")
async def submit_agent_query(
    prompt: str,
    delay_ms: int = 500,
    background_tasks: BackgroundTasks = None
) -> Dict[str, Any]:
    """
    Submit agent query to Celery task queue.
    Requires scaling workers to handle concurrency.
    """
    try:
        # Enqueue task (fire and forget for throughput benchmark)
        task = query_llm_task.delay(prompt, delay_ms)
        return {
            "status": "queued",
            "task_id": task.id,
            "message": "Query submitted to Celery queue"
        }
    except Exception as e:
        logger.error(f"Failed to enqueue task: {e}")
        return {"status": "error", "error": str(e)}


@app.post("/v1/agents/query/blocking")
async def submit_agent_query_blocking(
    prompt: str,
    delay_ms: int = 500
) -> Dict[str, Any]:
    """
    Submit and wait for agent query.
    This blocks the FastAPI worker thread while waiting for Celery.
    """
    try:
        start_time = time.time()
        task = query_llm_task.delay(prompt, delay_ms)
        
        # Wait for the worker result without blocking the async event loop.
        result = await asyncio.to_thread(task.get, timeout=30)
        
        elapsed_ms = (time.time() - start_time) * 1000
        result["elapsed_ms"] = elapsed_ms
        result["worker_type"] = "celery"
        
        return result
    except Exception as e:
        logger.error(f"Query failed: {e}")
        return {"status": "error", "error": str(e)}


@app.post("/v1/agents/stress-test")
async def stress_test(
    task_count: int = Query(default=10, alias="taskCount"),
    delay_ms: int = Query(default=100, alias="delayMs"),
) -> Dict[str, Any]:
    if not 1 <= task_count <= 1000:
        raise HTTPException(status_code=400, detail="task_count must be between 1 and 1000")
    if not 0 <= delay_ms <= 10000:
        raise HTTPException(status_code=400, detail="delay_ms must be between 0 and 10000")

    start = time.perf_counter()
    result = group(
        query_llm_task.s("benchmark", delay_ms)
        for _ in range(task_count)
    ).apply_async()
    await asyncio.to_thread(wait_for_group_results, result)
    total_time_ms = (time.perf_counter() - start) * 1000

    return {
        "status": "completed",
        "total_tasks": task_count,
        "total_time_ms": total_time_ms,
        "throughput_tasks_per_sec": task_count / (total_time_ms / 1000),
    }


def wait_for_group_results(result):
    with result_wait_lock:
        return result.join(timeout=300, propagate=True)


@app.get("/v1/health")
@app.get("/v1/agents/health")
async def health_check() -> Dict[str, str]:
    return {"status": "healthy"}


@app.get("/v1/metrics/celery")
async def celery_metrics() -> Dict[str, Any]:
    """Return basic Celery metrics"""
    inspect = celery_app.control.inspect()
    stats = inspect.stats()
    active = inspect.active()
    
    return {
        "workers": stats or {},
        "active_tasks": active or {},
        "timestamp": datetime.utcnow().isoformat(),
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001, workers=4)
