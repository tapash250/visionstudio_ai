#!/usr/bin/env python3
"""Local stand-in for a self-hosted provider (VisionStudio's "Custom JSON" dialect).

Lets the on-device checks in device-verify.sh observe network behaviour that the app itself
never logs: whether a render is submitted twice, whether polling resumes, and whether a cancel
request is sent. Every request is printed and appended to a JSON-lines log.

Run it on your computer, then map the port into the device:

    python3 android/scripts/mock_provider.py --port 8765
    adb reverse tcp:8765 tcp:8765

In the app's Settings, point a slot at it (cleartext to localhost is allowed by the app's
network security config):
    Protocol REST, Dialect Custom JSON, Auth None
    Imagine (images):  http://localhost:8765/v1/generate   -> answers at once with a PNG
    Motion (video):    http://localhost:8765/v1/generate   -> queues a job that keeps rendering
A video job never finishes unless started with --complete-after SECONDS; a completed job then
fails cleanly ("no media"), since this mock serves no real video file.
"""

import argparse
import base64
import json
import struct
import sys
import threading
import time
import uuid
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

JOBS = {}
LOCK = threading.Lock()


def png(width=64, height=64, rgb=(0x22, 0x55, 0xAA)):
    """A valid solid-colour PNG, built without third-party libraries."""
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    row = b"\x00" + bytes(rgb) * width
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height))
            + chunk(b"IEND", b""))


class Handler(BaseHTTPRequestHandler):
    server_version = "VisionStudioMock/1"

    def log_message(self, fmt, *args):  # replaced by record()
        pass

    def record(self, status, note=""):
        entry = {"t": round(time.time(), 3), "method": self.command, "path": self.path, "status": status, "note": note}
        line = json.dumps(entry)
        print(line, flush=True)
        with open(self.server.log_path, "a", encoding="utf-8") as log:
            log.write(line + "\n")

    def reply(self, status, payload, note=""):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.record(status, note)

    def base(self):
        return "http://localhost:%d" % self.server.server_address[1]

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            return {}

    def do_GET(self):
        parts = self.path.split("?")[0].strip("/").split("/")
        if parts[:2] == ["v1", "jobs"] and len(parts) == 3:
            with LOCK:
                job = JOBS.get(parts[2])
            if job is None:
                return self.reply(404, {"error": "unknown job"}, "poll unknown")
            if job["cancelled"]:
                return self.reply(200, {"status": "cancelled"}, "poll cancelled job")
            elapsed = time.time() - job["created"]
            limit = self.server.complete_after
            if limit is not None and elapsed >= limit:
                return self.reply(200, {"status": "succeeded", "output": []}, "poll done")
            progress = min(95, int(elapsed))
            return self.reply(200, {"status": "running", "progress": progress}, "poll %s" % parts[2])
        # Settings "Test" sends a GET to the endpoint itself.
        return self.reply(200, {"ok": True}, "probe")

    def do_POST(self):
        parts = self.path.split("?")[0].strip("/").split("/")
        payload = self.read_json()
        if parts[:2] == ["v1", "jobs"] and len(parts) == 4 and parts[3] == "cancel":
            with LOCK:
                job = JOBS.get(parts[2])
                if job is None:
                    return self.reply(404, {"error": "unknown job"}, "CANCEL unknown")
                already = job["cancelled"]
                job["cancelled"] = True
            return self.reply(200, {"status": "cancellation_requested"}, "CANCEL %s%s" % (parts[2], " (again)" if already else ""))
        if parts == ["v1", "generate"]:
            if payload.get("task") == "video_studio":
                job_id = "job-" + uuid.uuid4().hex[:8]
                with LOCK:
                    JOBS[job_id] = {"created": time.time(), "cancelled": False}
                return self.reply(200, {
                    "job_id": job_id,
                    "status": "queued",
                    "status_url": "%s/v1/jobs/%s" % (self.base(), job_id),
                    "cancel_url": "%s/v1/jobs/%s/cancel" % (self.base(), job_id),
                }, "SUBMIT video %s" % job_id)
            image = base64.b64encode(png()).decode()
            return self.reply(200, {"images": [{"b64_json": image, "content_type": "image/png"}]}, "SUBMIT image")
        return self.reply(404, {"error": "not found"}, "unknown route")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--log", default="mock_provider.log.jsonl", help="JSON-lines request log")
    parser.add_argument("--complete-after", type=float, default=None, metavar="SECONDS",
                        help="report video jobs as finished after this many seconds (default: never)")
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.log_path = args.log
    server.complete_after = args.complete_after
    print("mock provider on http://localhost:%d, logging to %s" % (args.port, args.log), file=sys.stderr, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
