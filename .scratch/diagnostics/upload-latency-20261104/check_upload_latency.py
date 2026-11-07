"""Read-only incident measurement, intended to run over SSH on internal host 124.

Only emits timestamps, request paths/statuses, object identifiers, and timings.
Default window: the preceding hour. --since accepts an ISO-8601 timestamp.
Exit 2 means an upload returned 504 or a TOS upload exceeded 60 seconds.
"""

import argparse
import collections
import datetime as dt
import json
from pathlib import Path
import re
import subprocess


parser = argparse.ArgumentParser()
parser.add_argument("--since")
options = parser.parse_args()
cst = dt.timezone(dt.timedelta(hours=8))
now = dt.datetime.now(cst)
since = dt.datetime.fromisoformat(options.since) if options.since else now - dt.timedelta(hours=1)
if since.tzinfo is None:
    since = since.replace(tzinfo=cst)
log_dir = Path("/opt/xzwl-test-124/logs")

http_events = []
for line in (log_dir / "nginx-access.log").open():
    if '"POST /api/v1/files/reference-media ' not in line:
        continue
    match = re.search(r'\[([^]]+)\] "POST (/api/v1/files/reference-media) HTTP/[^\"]+" (\d+)', line)
    if not match:
        continue
    stamp = dt.datetime.strptime(match[1], "%d/%b/%Y:%H:%M:%S %z")
    if stamp >= since:
        http_events.append({"time_cst": stamp.isoformat(), "path": match[2], "status": int(match[3])})

upload_body_events = []
for line in (log_dir / "nginx-error.log").open():
    if "reference-media" not in line or "a client request body is buffered" not in line:
        continue
    stamp = dt.datetime.strptime(line[:19], "%Y/%m/%d %H:%M:%S").replace(tzinfo=cst)
    if stamp >= since:
        upload_body_events.append(stamp.isoformat())

raw = subprocess.check_output([
    "journalctl", "-u", "xzwl-test-124-storage.service", "--since",
    since.astimezone(cst).strftime("%Y-%m-%d %H:%M:%S"), "--no-pager", "-o", "cat"
], text=True)
tos_events = []
exceptions = collections.Counter()
for line in raw.splitlines():
    if "tos: request exception:" in line:
        for name in ("ProtocolException", "SocketTimeoutException", "SocketException"):
            if name in line:
                exceptions[name] += 1
    if "[high latency request]" not in line or "method: PUT," not in line:
        continue
    total = re.search(r"request cost: (\d+) ms", line)
    sent = re.search(r"send headers and body cost: (\d+) ms", line)
    object_id = re.search(r"request uri: /objects%2F([^,]+)", line)
    tos_events.append({
        "time_cst": line[:29],
        "kind": "archive" if "archive-" in line else "upload",
        "object_id": object_id[1] if object_id else None,
        "request_ms": int(total[1]) if total else None,
        "send_ms": int(sent[1]) if sent else None,
        "has_request_id": "requestId: null," not in line,
    })

slow_uploads = [event for event in tos_events if event["kind"] == "upload" and (event["request_ms"] or 0) > 60000]
timeouts = [event for event in http_events if event["status"] == 504]
result = {
    "measured_at_cst": now.isoformat(),
    "since_cst": since.isoformat(),
    "environment": "internal 115.29.222.124:32124",
    "verdict": "RED" if timeouts or slow_uploads else "NO_SLOW_UPLOAD_OBSERVED",
    "interpretation": "Log observation only; no recent uploads is not proof of recovery.",
    "upload_http_status_counts": dict(collections.Counter(event["status"] for event in http_events)),
    "upload_http_events": http_events,
    "request_body_observed_at_cst": upload_body_events,
    "tos_upload_events": [event for event in tos_events if event["kind"] == "upload"],
    "tos_archive_slow_request_count": sum(event["kind"] == "archive" for event in tos_events),
    "tos_exception_counts": dict(exceptions),
    "sources": [str(log_dir / "nginx-access.log"), str(log_dir / "nginx-error.log"), "journalctl -u xzwl-test-124-storage.service"],
}
print(json.dumps(result, indent=2))
raise SystemExit(2 if timeouts or slow_uploads else 0)
