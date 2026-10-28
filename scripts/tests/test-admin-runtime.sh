#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
launcher="$repo_root/scripts/admin-runtime.sh"

test -x "$launcher"
grep -q 'strictPort' "$launcher"
grep -q '127.0.0.1' "$launcher"
grep -q '5176' "$launcher"
grep -q '"dev": "vite"' "$repo_root/frontends/admin-web/package.json"
test -x "$repo_root/scripts/admin-runtime-smoke.sh"

echo "admin runtime contract passed"
