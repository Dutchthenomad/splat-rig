#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import threading
import uuid
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from pipeline import process_zip

ROOT = Path(__file__).resolve().parent
RUNS = ROOT / "runs"
VIEWER = ROOT.parent / "viewer"
RUNS.mkdir(exist_ok=True)

app = FastAPI(title="SplatRig worker")
jobs: dict[str, dict] = {}


def _run(job_id: str, zip_path: Path) -> None:
    jobs[job_id]["status"] = "running"
    try:
        meta = process_zip(zip_path, RUNS / job_id)
        jobs[job_id].update(meta)
        jobs[job_id]["status"] = "ready"
    except Exception as exc:
        jobs[job_id]["status"] = "failed"
        jobs[job_id]["error"] = str(exc)


@app.post("/sessions")
async def upload(file: UploadFile = File(...)):
    job_id = uuid.uuid4().hex[:12]
    dest_dir = RUNS / job_id
    dest_dir.mkdir(parents=True, exist_ok=True)
    zip_path = dest_dir / "session.zip"
    with zip_path.open("wb") as f:
        shutil.copyfileobj(file.file, f)
    jobs[job_id] = {"id": job_id, "status": "uploaded", "zip": str(zip_path)}
    return jobs[job_id]


@app.post("/sessions/{job_id}/run")
def run(job_id: str):
    job = jobs.get(job_id)
    if not job:
        raise HTTPException(404, "unknown session")
    if job["status"] in {"running"}:
        return job
    t = threading.Thread(target=_run, args=(job_id, Path(job["zip"])), daemon=True)
    t.start()
    job["status"] = "queued"
    return job


@app.get("/sessions/{job_id}")
def status(job_id: str):
    job = jobs.get(job_id)
    if not job:
        raise HTTPException(404, "unknown session")
    return job


@app.get("/sessions/{job_id}/splat.ply")
def splat(job_id: str):
    ply = RUNS / job_id / "train" / "splat.ply"
    if not ply.exists():
        raise HTTPException(404, "splat not ready")
    return FileResponse(ply, filename=f"{job_id}.ply")


if VIEWER.exists():
    app.mount("/view", StaticFiles(directory=VIEWER, html=True), name="view")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    import uvicorn

    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
