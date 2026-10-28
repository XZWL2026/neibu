#!/usr/bin/env bash
set -euo pipefail
# Run only the native executor. Existing Java media/storage and Gateway remain separately managed.
: "${DEPTH_PYTHON:?Set DEPTH_PYTHON to the depth virtualenv python}"
: "${DEPTH_MODEL_PATH:?Set DEPTH_MODEL_PATH to the local Depth Anything V2 Small weights directory}"
: "${MEDIA_DEPTH_TOKEN:?Set a private random token shared with media-service}"
root=$(cd "$(dirname "$0")/../.." && pwd)
exec "$DEPTH_PYTHON" "$root/services/media-service/depth-worker/worker.py" \
  --model "$DEPTH_MODEL_PATH" --jobs "${DEPTH_JOBS_PATH:-${TMPDIR:-/tmp}/xzwl-depth-jobs}" \
  --device "${DEPTH_DEVICE:-auto}" --port "${DEPTH_PORT:-18727}"
