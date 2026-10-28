#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
port=${ADMIN_PORT:-5176}

cd "$repo_root"
exec npm run dev --workspace @xzwl/admin-web -- --host 127.0.0.1 --port "$port" --strictPort
