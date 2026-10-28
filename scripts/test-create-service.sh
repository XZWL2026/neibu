#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
generator="$repo_root/scripts/create-service.sh"
export XZWL_CREATE_SERVICE_TEST_MODE=1

command -v python3 >/dev/null 2>&1 || {
  printf 'python3 is required by scripts/test-create-service.sh\n' >&2
  exit 1
}
python3 - <<'PY' || {
import ctypes
import os
import signal
import sys

if sys.version_info < (3, 9):
    raise SystemExit(1)
try:
    import fcntl
except Exception:
    raise SystemExit(1)
if not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit(1)
if not hasattr(os, "O_DIRECTORY") or not hasattr(signal, "pthread_sigmask"):
    raise SystemExit(1)
libc = ctypes.CDLL(None)
if sys.platform.startswith("linux"):
    supported = hasattr(libc, "renameat2")
elif sys.platform == "darwin":
    supported = hasattr(libc, "renameatx_np")
else:
    supported = False
if not supported:
    raise SystemExit(1)
PY
  printf 'Python 3.9+ with POSIX fcntl/O_NOFOLLOW/O_DIRECTORY/pthread_sigmask and Linux renameat2 or Darwin renameatx_np is required by scripts/test-create-service.sh\n' >&2
  exit 1
}

if [[ ! -f "$generator" ]]; then
  printf 'missing service generator: scripts/create-service.sh\n' >&2
  exit 1
fi

test_root="$(mktemp -d "${TMPDIR:-/tmp}/xzwl-create-service-test.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT

# Reserve a high port range exclusively for this script's generated fixtures.
test_port_base=61000
python3 - "$repo_root/architecture/service-catalog.yaml" "$test_port_base" <<'PY'
import json
import sys

catalog_path, base = sys.argv[1], int(sys.argv[2])
ports = {entry["port"] for entry in json.load(open(catalog_path, encoding="utf-8"))["services"]}
reserved = set(range(base, base + 100))
overlap = sorted(ports & reserved)
if overlap:
    raise SystemExit(f"test-only port range {base}-{base + 99} is occupied by catalog ports: {overlap}")
PY

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/scripts" "$fixture/architecture"
  cp "$repo_root/pom.xml" "$fixture/pom.xml"
  cp "$repo_root/mvnw" "$fixture/mvnw"
  cp "$generator" "$fixture/scripts/create-service.sh"
  cp "$repo_root/scripts/verify-version-drift.sh" "$fixture/scripts/verify-version-drift.sh"
  cp "$repo_root/architecture/service-catalog.yaml" \
    "$fixture/architecture/service-catalog.yaml"
  cp -R "$repo_root/.mvn" "$fixture/.mvn"
  cp -R "$repo_root/platform" "$fixture/platform"
  cp -R "$repo_root/gateways" "$fixture/gateways"
  cp -R "$repo_root/services" "$fixture/services"
  cp -R "$repo_root/service-template" "$fixture/service-template"
  chmod +x "$fixture/mvnw" "$fixture/scripts/create-service.sh"
  find "$fixture" -type d -name target -prune -exec rm -rf {} +
  assert_fixture_modules_exist "$fixture"
}

assert_fixture_modules_exist() {
  python3 - "$1" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(root / "pom.xml").getroot()
for element in pom.findall("m:modules/m:module", namespace):
    module = (element.text or "").strip()
    if not module:
        raise SystemExit("fixture root POM contains an empty module path")
    if not (root / module).exists():
        raise SystemExit(f"fixture is missing root POM module: {module}")
PY
}

snapshot_state() {
  python3 - "$1" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys

root = Path(sys.argv[1])
digest = sha256()
for relative in (Path("pom.xml"), Path("architecture/service-catalog.yaml"), Path("services")):
    path = root / relative
    candidates = [path] if path.is_file() or path.is_symlink() else sorted(path.rglob("*"))
    for candidate in candidates:
        rel = candidate.relative_to(root).as_posix()
        digest.update(rel.encode("utf-8"))
        if candidate.is_symlink():
            digest.update(b"L")
            digest.update(candidate.readlink().as_posix().encode("utf-8"))
        elif candidate.is_file():
            digest.update(b"F")
            digest.update(candidate.read_bytes())
        elif candidate.is_dir():
            digest.update(b"D")
print(digest.hexdigest())
PY
}

snapshot_recovery_state() {
  python3 - "$1" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys

root = Path(sys.argv[1])
digest = sha256()
paths = [root / "pom.xml", root / "architecture/service-catalog.yaml", root / "services"]
paths.extend(sorted(root.glob(".create-service.*")))
for path in paths:
    candidates = [path] if path.is_file() or path.is_symlink() else [path, *sorted(path.rglob("*"))]
    for candidate in candidates:
        rel = candidate.relative_to(root).as_posix()
        digest.update(rel.encode("utf-8"))
        info = candidate.lstat()
        digest.update(str((info.st_dev, info.st_ino, info.st_mode)).encode("ascii"))
        if candidate.is_symlink():
            digest.update(b"L")
            digest.update(candidate.readlink().as_posix().encode("utf-8"))
        elif candidate.is_file():
            digest.update(b"F")
            digest.update(candidate.read_bytes())
        elif candidate.is_dir():
            digest.update(b"D")
print(digest.hexdigest())
PY
}

assert_no_transaction_artifacts() {
  local fixture="$1"
  local artifact
  for artifact in "$fixture"/.create-service.*; do
    if [[ -e "$artifact" || -L "$artifact" ]]; then
      printf 'generator left transaction artifact: %s\n' "$artifact" >&2
      exit 1
    fi
  done
  local holding_root
  holding_root="$(stable_holding_root "$fixture")"
  if [[ -e "$holding_root" || -L "$holding_root" ]]; then
    [[ -d "$holding_root" && ! -L "$holding_root" ]] || {
      printf 'stable holding root is not a real directory: %s\n' "$holding_root" >&2
      exit 1
    }
    [[ -z "$(find "$holding_root" -mindepth 1 -maxdepth 1 -print -quit)" ]] || {
      printf 'stable holding root retained transaction children: %s\n' "$holding_root" >&2
      exit 1
    }
  fi
}

stable_holding_root() {
  python3 - "$1" <<'PY'
from hashlib import sha256
from pathlib import Path
import os
import sys

root = Path(sys.argv[1]).resolve(strict=True)
info = root.stat()
identity = os.fsencode(str(root)) + b"\0" + f"{info.st_dev}:{info.st_ino}".encode("ascii")
token = sha256(identity).hexdigest()[:32]
print(root.parent / f".xzwl-create-service-holding-{token}")
PY
}

native_probe_path() {
  local holding_root
  holding_root="$(stable_holding_root "$1")"
  find "$holding_root" -maxdepth 1 -name '.create-service.native-probe-*' \
    -type d -print -quit 2>/dev/null || true
}

expect_failure_preserves() {
  local fixture="$1"
  local expected="$2"
  local before
  local after
  local output
  local raw_exit
  shift 2
  before="$(snapshot_state "$fixture")"
  set +e
  output="$(cd "$fixture" && bash scripts/create-service.sh "$@" 2>&1)"
  raw_exit=$?
  set -e
  if [[ "$raw_exit" -eq 0 ]]; then
    printf 'expected generator failure containing %s: %s\n' "$expected" "$*" >&2
    exit 1
  fi
  [[ "$output" == *"$expected"* ]] || {
    printf 'missing generator diagnostic %s: %s\n' "$expected" "$output" >&2
    exit 1
  }
  after="$(snapshot_state "$fixture")"
  [[ "$before" == "$after" ]] || {
    printf 'failed generation changed repository state: %s\n' "$*" >&2
    exit 1
  }
  assert_no_transaction_artifacts "$fixture"
}

assert_generated_service() {
  local fixture="$1"
  local name="$2"
  local port="$3"
  local schema="$4"
  python3 - "$fixture" "$name" "$port" "$schema" <<'PY'
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
name = sys.argv[2]
port = int(sys.argv[3])
schema = sys.argv[4]
service = root / "services" / f"{name}-service"
if (service / ".xzwl-create-service-owner").exists():
    raise SystemExit("generated service retained a transaction ownership marker")
modules = ("api", "domain", "application", "infrastructure", "interfaces", "bootstrap")
for module in modules:
    module_root = service / f"{name}-{module}"
    if not module_root.is_dir() or not (module_root / "pom.xml").is_file():
        raise SystemExit(f"missing generated module {name}-{module}")

package_path = Path("com/xzwl/cloud") / Path(*name.split("-"))
required_java = {
    "domain": package_path / "domain/capability/Capability.java",
    "application": package_path / "application/capability/GetCapabilityQuery.java",
    "interfaces": package_path / "interfaces/http/CapabilityController.java",
    "bootstrap": package_path / "TemplateApplication.java",
}
for module, relative in required_java.items():
    source = service / f"{name}-{module}" / "src/main/java" / relative
    if not source.is_file():
        raise SystemExit(f"missing generated Java source {source.relative_to(root)}")
    expected_package = "com.xzwl.cloud." + name.replace("-", ".")
    if f"package {expected_package}" not in source.read_text(encoding="utf-8"):
        raise SystemExit(f"wrong generated Java package in {source.relative_to(root)}")

namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
application_pom = ET.parse(service / f"{name}-application" / "pom.xml").getroot()
application_dependencies = {
    (
        dependency.findtext("m:groupId", default="", namespaces=namespace),
        dependency.findtext("m:artifactId", default="", namespaces=namespace),
    )
    for dependency in application_pom.findall("m:dependencies/m:dependency", namespace)
}
expected_application_dependencies = {
    ("com.xzwl.cloud", f"{name}-api"),
    ("com.xzwl.cloud", f"{name}-domain"),
}
if application_dependencies != expected_application_dependencies:
    raise SystemExit(
        "generated application dependencies must be exactly api and domain: "
        f"{sorted(application_dependencies)!r}"
    )

root_pom = ET.parse(root / "pom.xml").getroot()
root_modules = [
    (element.text or "").strip()
    for element in root_pom.findall("m:modules/m:module", namespace)
]
expected_module = f"services/{name}-service"
if root_modules.count(expected_module) != 1:
    raise SystemExit(f"root POM must contain {expected_module} exactly once")

catalog_path = root / "architecture/service-catalog.yaml"
def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result

catalog = json.loads(catalog_path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)
matches = [entry for entry in catalog["services"] if entry.get("service") == f"{name}-service"]
if len(matches) != 1:
    raise SystemExit("generated catalog entry must appear exactly once")
expected_entry = {
    "service": f"{name}-service",
    "owner": "platform-team",
    "schema": schema,
    "port": port,
    "publicApi": [],
    "internalApi": [],
    "eventsPublished": [],
    "eventsConsumed": [],
    "syncDependencies": [],
}
if matches[0] != expected_entry:
    raise SystemExit(f"generated catalog entry differs: {matches[0]!r}")

configuration = (
    service
    / f"{name}-bootstrap"
    / "src/main/resources/application.yml"
).read_text(encoding="utf-8")
expected_configuration = (
    "spring:\n"
    "  application:\n"
    f"    name: {name}-service\n"
    "server:\n"
    f"  port: {port}\n"
)
if configuration != expected_configuration:
    raise SystemExit("generated executable configuration does not contain service name and port")
PY
}

wait_for_test_path() {
  local path="$1"
  local child_pid="$2"
  local attempt
  for attempt in $(seq 1 500); do
    [[ -e "$path" ]] && return 0
    if ! kill -0 "$child_pid" 2>/dev/null; then
      return 1
    fi
    sleep 0.01
  done
  return 1
}

assert_metadata_bytes() {
  local fixture="$1"
  local pom_copy="$2"
  local catalog_copy="$3"
  cmp -s "$fixture/pom.xml" "$pom_copy" || {
    printf 'root POM bytes changed unexpectedly\n' >&2
    exit 1
  }
  cmp -s "$fixture/architecture/service-catalog.yaml" "$catalog_copy" || {
    printf 'service catalog bytes changed unexpectedly\n' >&2
    exit 1
  }
}

pending_journal_path() {
  local fixture="$1"
  local holding_root
  local journal
  holding_root="$(stable_holding_root "$fixture")"
  journal="$({
    find "$fixture" -mindepth 2 -maxdepth 2 -name RECOVERY.json -type f -print
    if [[ -d "$holding_root" && ! -L "$holding_root" ]]; then
      find "$holding_root" -mindepth 2 -maxdepth 2 -name RECOVERY.json -type f -print
    fi
  } | sort)"
  [[ "$(printf '%s\n' "$journal" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 1 ]] || {
    printf 'expected exactly one pending recovery journal in %s\n' "$fixture" >&2
    return 1
  }
  printf '%s\n' "$journal"
}

assert_journal_stage() {
  python3 - "$1" "$2" <<'PY'
from pathlib import Path
import json
import sys

journal = Path(sys.argv[1])
expected = sys.argv[2]
actual = json.loads(journal.read_text(encoding="utf-8")).get("stage")
if actual != expected:
    raise SystemExit(f"expected journal stage {expected}, got {actual}")
PY
}

crash_transaction() {
  local fixture="$1"
  local name="$2"
  local port="$3"
  local schema="$4"
  local point="$5"
  local output="$6"
  local raw_exit
  make_fixture "$fixture"
  set +e
  (cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_FAULTS="crash-$point" \
    bash scripts/create-service.sh --name "$name" --port "$port" --schema "$schema") \
    >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 97 ]] || {
    printf 'expected crash point %s to exit 97, raw=%s output=%s\n' \
      "$point" "$raw_exit" "$(cat "$output")" >&2
    return 1
  }
}

test_java_package_keywords() {
  local fixture="$test_root/java-package-keywords"
  local reserved_names=(
    _ abstract assert boolean break byte case catch char class const continue default do
    double else enum exports extends false final finally float for goto if implements import
    instanceof int interface long module native new non-sealed null open opens package permits
    private protected provides public record requires return sealed short static strictfp super
    switch synchronized this throw throws to transient transitive true try uses var void volatile
    when while with yield
  )
  make_fixture "$fixture"
  [[ "${#reserved_names[@]}" -eq 71 ]] || {
    printf 'Java 21 reserved/restricted identifier matrix must contain 71 names\n' >&2
    exit 1
  }
  for keyword in "${reserved_names[@]}"; do
    expect_failure_preserves "$fixture" 'invalid service name' \
      --name "$keyword" --port 8231 --schema "${keyword//-/_}_db"
  done
  (cd "$fixture" && bash scripts/create-service.sh \
    --name classic-adapter --port 8231 --schema classic_adapter_db)
  assert_generated_service "$fixture" classic-adapter 8231 classic_adapter_db
}

test_rollback_continues_after_restore_failure() {
  local fixture="$test_root/rollback-of-rollback"
  local before
  local output
  local raw_exit
  make_fixture "$fixture"
  before="$(snapshot_state "$fixture")"
  set +e
  output="$(cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_FAULTS='after-catalog-replace,catalog-restore-once' \
    bash scripts/create-service.sh --name restore-once --port 8232 \
      --schema restore_once_db 2>&1)"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 \
    && "$output" == *'injected failure after catalog replace'* \
    && "$output" == *'rollback completed'* ]] || {
    printf 'rollback-of-rollback did not report a recovered failure: raw=%s output=%s\n' \
      "$raw_exit" "$output" >&2
    exit 1
  }
  [[ "$before" == "$(snapshot_state "$fixture")" ]] || {
    printf 'rollback-of-rollback left a partial repository commit\n' >&2
    exit 1
  }
  assert_no_transaction_artifacts "$fixture"

  fixture="$test_root/unrecoverable-rollback"
  make_fixture "$fixture"
  set +e
  output="$(cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_FAULTS='after-catalog-replace,catalog-restore-always' \
    bash scripts/create-service.sh --name restore-never --port 8233 \
      --schema restore_never_db 2>&1)"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 && "$output" == *'recovery incomplete; journal retained at '* ]] || {
    printf 'unrecoverable rollback lacked retained-journal diagnostic: raw=%s output=%s\n' \
      "$raw_exit" "$output" >&2
    exit 1
  }
  test ! -e "$fixture/.create-service.lock"
  test ! -e "$fixture/services/restore-never-service"
  local journal_count
  journal_count="$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')"
  [[ "$journal_count" -eq 1 ]] || {
    printf 'unrecoverable rollback must retain exactly one recovery journal\n' >&2
    exit 1
  }
  test "$(find "$fixture" -maxdepth 2 -name pom.backup -type f | wc -l | tr -d ' ')" -eq 1
  test "$(find "$fixture" -maxdepth 2 -name catalog.backup -type f | wc -l | tr -d ' ')" -eq 1
}

test_target_publish_race() {
  local fixture="$test_root/target-publish-race"
  local hook_dir="$test_root/target-race-hook"
  local output="$test_root/target-race.log"
  local pom_before="$test_root/target-race-pom.before"
  local catalog_before="$test_root/target-race-catalog.before"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  cp "$fixture/pom.xml" "$pom_before"
  cp "$fixture/architecture/service-catalog.yaml" "$catalog_before"
  set +e
  (cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
    XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-target-native-publish' \
    bash scripts/create-service.sh --name publish-race --port 8234 \
      --schema publish_race_db) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/before-target-native-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'target race RED: generator did not expose the native publication point\n' >&2
    exit 1
  }
  mkdir "$fixture/services/publish-race-service"
  printf 'third-party-bytes\n' > "$fixture/services/publish-race-service/third-party"
  : > "$hook_dir/before-target-native-publish.continue"
  set +e
  wait "$child"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'target publication race unexpectedly succeeded\n' >&2
    exit 1
  }
  test "$(cat "$fixture/services/publish-race-service/third-party")" = 'third-party-bytes'
  test ! -e "$fixture/services/publish-race-service/publish-race-service"
  assert_metadata_bytes "$fixture" "$pom_before" "$catalog_before"
  assert_no_transaction_artifacts "$fixture"
}

test_metadata_snapshot_race() {
  local fixture="$test_root/metadata-snapshot-race"
  local hook_dir="$test_root/metadata-race-hook"
  local output="$test_root/metadata-race.log"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
    XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-metadata-compare' \
    bash scripts/create-service.sh --name metadata-race --port 8235 \
      --schema metadata_race_db) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/before-metadata-compare.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'metadata race RED: generator did not expose the pre-commit comparison point\n' >&2
    exit 1
  }
  python3 - "$fixture/pom.xml" "$fixture/architecture/service-catalog.yaml" <<'PY'
from pathlib import Path
import json
import sys

pom = Path(sys.argv[1])
catalog = Path(sys.argv[2])
pom.write_text(
    pom.read_text(encoding="utf-8").replace(
        "</project>", "  <!-- external-update -->\n</project>"
    ),
    encoding="utf-8",
)
data = json.loads(catalog.read_text(encoding="utf-8"))
catalog.write_text(json.dumps(data, indent=4) + "\n", encoding="utf-8")
PY
  : > "$hook_dir/before-metadata-compare.continue"
  set +e
  wait "$child"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'metadata race silently overwrote a concurrent update\n' >&2
    exit 1
  }
  grep -F '<!-- external-update -->' "$fixture/pom.xml" >/dev/null
  test ! -e "$fixture/services/metadata-race-service"
  assert_no_transaction_artifacts "$fixture"
}

start_barrier_child() {
  local fixture="$1"
  local barrier="$2"
  local participant="$3"
  local name="$4"
  local port="$5"
  local schema="$6"
  local log="$7"
  (
    : > "$barrier/$participant.ready"
    while [[ ! -e "$barrier/start" ]]; do sleep 0.01; done
    cd "$fixture"
    XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$barrier" \
    XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-lock' \
    XZWL_CREATE_SERVICE_TEST_PARTICIPANT="$participant" \
      bash scripts/create-service.sh --name "$name" --port "$port" --schema "$schema"
  ) >"$log" 2>&1 &
  BARRIER_CHILD_PID=$!
}

run_barrier_pair() {
  local fixture="$1"
  local pair="$2"
  local first_name="$3"
  local first_port="$4"
  local first_schema="$5"
  local second_name="$6"
  local second_port="$7"
  local second_schema="$8"
  local barrier="$test_root/barrier-$pair"
  local first_log="$test_root/barrier-$pair-one.log"
  local second_log="$test_root/barrier-$pair-two.log"
  local first_pid
  local second_pid
  local first_exit
  local second_exit
  rm -rf "$barrier"
  mkdir -p "$barrier"
  start_barrier_child "$fixture" "$barrier" one \
    "$first_name" "$first_port" "$first_schema" "$first_log"
  first_pid="$BARRIER_CHILD_PID"
  start_barrier_child "$fixture" "$barrier" two \
    "$second_name" "$second_port" "$second_schema" "$second_log"
  second_pid="$BARRIER_CHILD_PID"
  wait_for_test_path "$barrier/one.ready" "$first_pid"
  wait_for_test_path "$barrier/two.ready" "$second_pid"
  : > "$barrier/start"
  local lock_holder=''
  local attempt
  for attempt in $(seq 1 500); do
    [[ -e "$barrier/after-lock.one.ready" ]] && lock_holder=one && break
    [[ -e "$barrier/after-lock.two.ready" ]] && lock_holder=two && break
    sleep 0.01
  done
  [[ -n "$lock_holder" ]] || {
    wait "$first_pid" 2>/dev/null || true
    wait "$second_pid" 2>/dev/null || true
    printf 'concurrency RED: no child held the transaction lock at the test barrier\n' >&2
    exit 1
  }
  : > "$barrier/after-lock.continue"
  set +e
  wait "$first_pid"; first_exit=$?
  wait "$second_pid"; second_exit=$?
  set -e
  if [[ "$first_exit" -eq 0 && "$second_exit" -ne 0 ]]; then
    grep -Fx 'another service generation is in progress' "$second_log" >/dev/null || {
      printf 'concurrency loser did not fail for the global lock: %s\n' "$(cat "$second_log")" >&2
      exit 1
    }
    BARRIER_WINNER=one
  elif [[ "$second_exit" -eq 0 && "$first_exit" -ne 0 ]]; then
    grep -Fx 'another service generation is in progress' "$first_log" >/dev/null || {
      printf 'concurrency loser did not fail for the global lock: %s\n' "$(cat "$first_log")" >&2
      exit 1
    }
    BARRIER_WINNER=two
  else
    printf 'barrier pair %s must produce one lock winner and one loser: %s/%s\n' \
      "$pair" "$first_exit" "$second_exit" >&2
    exit 1
  fi
}

test_concurrency_barrier_and_global_lock() {
  local fixture="$test_root/barrier-concurrency"
  local index
  make_fixture "$fixture"
  for index in 1 2 3 4 5 6 7 8; do
    run_barrier_pair "$fixture" "same-$index" "barrier$index" "$((8240 + index))" \
      "barrier${index}_db" "barrier$index" "$((8240 + index))" "barrier${index}_db"
    assert_generated_service "$fixture" "barrier$index" "$((8240 + index))" "barrier${index}_db"
  done
  run_barrier_pair "$fixture" different \
    global-one 8250 global_one_db global-two 8251 global_two_db
  if [[ "$BARRIER_WINNER" == one ]]; then
    (cd "$fixture" && bash scripts/create-service.sh \
      --name global-two --port 8251 --schema global_two_db)
  else
    (cd "$fixture" && bash scripts/create-service.sh \
      --name global-one --port 8250 --schema global_one_db)
  fi
  assert_generated_service "$fixture" global-one 8250 global_one_db
  assert_generated_service "$fixture" global-two 8251 global_two_db
  assert_no_transaction_artifacts "$fixture"
}

test_lock_symlink_open_race() {
  local fixture="$test_root/lock-open-race"
  local hook_dir="$test_root/lock-open-hook"
  local output="$test_root/lock-open-race.log"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
    XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-lock-open' \
    bash scripts/create-service.sh --name lock-race --port 8252 \
      --schema lock_race_db) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/before-lock-open.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'lock race RED: generator has a check-then-open lock path without a safe-open seam\n' >&2
    exit 1
  }
  ln -s pom.xml "$fixture/.create-service.lock"
  : > "$hook_dir/before-lock-open.continue"
  set +e
  wait "$child"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'stable repository lock failed because of an unrelated legacy lock symlink: %s\n' \
      "$(cat "$output")" >&2
    exit 1
  }
  test -L "$fixture/.create-service.lock"
  [[ "$(readlink "$fixture/.create-service.lock")" == pom.xml ]]
  assert_generated_service "$fixture" lock-race 8252 lock_race_db
  rm "$fixture/.create-service.lock"
  assert_no_transaction_artifacts "$fixture"
}

test_signal_before_final_publish_rolls_back() {
  local fixture="$test_root/signal-before-final-publish"
  local hook_dir="$test_root/signal-before-final-publish-hook"
  local output="$test_root/signal-before-final-publish.log"
  local before
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  before="$(snapshot_state "$fixture")"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-final-publish' \
      bash scripts/create-service.sh --name signal-before --port 8253 \
        --schema signal_before_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/before-final-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'pre-commit signal test did not reach the final-publication boundary\n' >&2
    exit 1
  }
  kill -TERM "$child"
  set +e
  wait "$child"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'signal during transaction unexpectedly reported success\n' >&2
    exit 1
  }
  grep -F 'service generation interrupted by signal' "$output" >/dev/null
  [[ "$before" == "$(snapshot_state "$fixture")" ]] || {
    printf 'pre-commit signal left metadata, reservation, or target changes\n' >&2
    exit 1
  }
  assert_no_transaction_artifacts "$fixture"
}

test_signal_after_commit_preserves_third_party() {
  local fixture="$test_root/signal-after-commit"
  local hook_dir="$test_root/signal-after-commit-hook"
  local output="$test_root/signal-after-commit.log"
  local third_party="$fixture/services/third-party-race-service/third-party.txt"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-target-publish' \
      bash scripts/create-service.sh --name third-party-race --port 8254 \
        --schema third_party_race_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/after-target-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'post-commit signal test did not reach committed publication\n' >&2
    exit 1
  }
  printf 'third-party-survives\n' > "$third_party"
  kill -TERM "$child"
  set +e
  wait "$child"
  raw_exit=$?
  set -e
  [[ -f "$third_party" && "$(cat "$third_party")" == 'third-party-survives' ]] || {
    printf 'post-commit signal deleted third-party bytes: raw=%s target_survived=%s\n' \
      "$raw_exit" "$([[ -d "$fixture/services/third-party-race-service" ]] && printf yes || printf no)" >&2
    exit 1
  }
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'post-commit signal must report committed success: raw=%s output=%s\n' \
      "$raw_exit" "$(cat "$output")" >&2
    exit 1
  }
  grep -F 'service generation was already committed before signal' "$output" >/dev/null
  assert_generated_service "$fixture" third-party-race 8254 third_party_race_db
  assert_no_transaction_artifacts "$fixture"
}

test_signal_is_deferred_across_metadata_to_publish() {
  local fixture="$test_root/signal-deferred-commit"
  local hook_dir="$test_root/signal-deferred-commit-hook"
  local output="$test_root/signal-deferred-commit.log"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='metadata-committed-before-final-publish' \
      bash scripts/create-service.sh --name deferred-signal --port 8255 \
        --schema deferred_signal_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/metadata-committed-before-final-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'deferred-signal test did not reach metadata-to-publish critical section\n' >&2
    exit 1
  }
  grep -F 'services/deferred-signal-service' "$fixture/pom.xml" >/dev/null
  grep -F '"service": "deferred-signal-service"' \
    "$fixture/architecture/service-catalog.yaml" >/dev/null
  test ! -e "$fixture/services/deferred-signal-service"
  kill -TERM "$child"
  : > "$hook_dir/metadata-committed-before-final-publish.continue"
  set +e
  wait "$child"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'deferred signal prevented final committed publication: raw=%s output=%s\n' \
      "$raw_exit" "$(cat "$output")" >&2
    exit 1
  }
  grep -F 'service generation was already committed before signal' "$output" >/dev/null
  assert_generated_service "$fixture" deferred-signal 8255 deferred_signal_db
  assert_no_transaction_artifacts "$fixture"
}

test_target_race_after_metadata_commit_rolls_back_metadata_only() {
  local fixture="$test_root/target-race-after-metadata"
  local hook_dir="$test_root/target-race-after-metadata-hook"
  local output="$test_root/target-race-after-metadata.log"
  local pom_before="$test_root/target-race-after-metadata-pom.before"
  local catalog_before="$test_root/target-race-after-metadata-catalog.before"
  local third_party="$fixture/services/late-race-service/third-party.txt"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  cp "$fixture/pom.xml" "$pom_before"
  cp "$fixture/architecture/service-catalog.yaml" "$catalog_before"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='metadata-committed-before-final-publish' \
      bash scripts/create-service.sh --name late-race --port 8256 \
        --schema late_race_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/metadata-committed-before-final-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'late target race did not reach metadata-to-publish critical section\n' >&2
    exit 1
  }
  mkdir "$fixture/services/late-race-service"
  printf 'late-third-party-survives\n' > "$third_party"
  : > "$hook_dir/metadata-committed-before-final-publish.continue"
  set +e
  wait "$child"
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'late target race unexpectedly committed\n' >&2
    exit 1
  }
  grep -F 'target publication failed without overwrite' "$output" >/dev/null
  [[ -f "$third_party" && "$(cat "$third_party")" == 'late-third-party-survives' ]]
  test ! -e "$fixture/services/late-race-service/late-race-api"
  assert_metadata_bytes "$fixture" "$pom_before" "$catalog_before"
  assert_no_transaction_artifacts "$fixture"
}

inode_key() {
  python3 - "$1" <<'PY'
from pathlib import Path
import os
import sys

info = os.lstat(Path(sys.argv[1]))
print(f"{info.st_dev}:{info.st_ino}")
PY
}

test_stable_repo_directory_lock() {
  local fixture="$test_root/stable-repo-lock"
  local hook_dir="$test_root/stable-repo-lock-hook"
  local first_log="$test_root/stable-repo-lock-first.log"
  local second_log="$test_root/stable-repo-lock-second.log"
  local first_pid
  local second_pid
  local first_exit
  local second_exit
  local first_entered=false
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='lock-verified-before-flock,after-lock' \
      XZWL_CREATE_SERVICE_TEST_PARTICIPANT=one \
      bash scripts/create-service.sh --name stable-one --port 8260 --schema stable_one_db
  ) >"$first_log" 2>&1 &
  first_pid=$!
  set -e
  wait_for_test_path "$hook_dir/lock-verified-before-flock.one.ready" "$first_pid" || {
    wait "$first_pid" 2>/dev/null || true
    printf 'stable-directory-lock RED: missing verified-before-flock seam\n' >&2
    exit 1
  }
  rm -f "$fixture/.create-service.lock"
  printf 'third-party-lock-path\n' > "$fixture/.create-service.lock"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-lock' \
      XZWL_CREATE_SERVICE_TEST_PARTICIPANT=two \
      bash scripts/create-service.sh --name stable-two --port 8261 --schema stable_two_db
  ) >"$second_log" 2>&1 &
  second_pid=$!
  set -e
  wait_for_test_path "$hook_dir/after-lock.two.ready" "$second_pid" || {
    : > "$hook_dir/lock-verified-before-flock.continue"
    wait "$first_pid" 2>/dev/null || true
    wait "$second_pid" 2>/dev/null || true
    printf 'stable-directory-lock RED: second generator did not acquire the global lock\n' >&2
    exit 1
  }
  : > "$hook_dir/lock-verified-before-flock.continue"
  for _ in $(seq 1 500); do
    if [[ -e "$hook_dir/after-lock.one.ready" ]]; then
      first_entered=true
      break
    fi
    if ! kill -0 "$first_pid" 2>/dev/null; then
      break
    fi
    sleep 0.01
  done
  : > "$hook_dir/after-lock.continue"
  set +e
  wait "$first_pid"; first_exit=$?
  wait "$second_pid"; second_exit=$?
  set -e
  [[ "$first_entered" == false \
    && "$first_exit" -ne 0 \
    && "$second_exit" -eq 0 \
    && "$(cat "$first_log")" == *'another service generation is in progress'* ]] || {
    printf 'stable-directory-lock RED: split lock admitted both critical sections: entered=%s raw=%s/%s\n' \
      "$first_entered" "$first_exit" "$second_exit" >&2
    exit 1
  }
  [[ "$(cat "$fixture/.create-service.lock")" == 'third-party-lock-path' ]] || {
    printf 'stable-directory-lock removed or changed a third-party lock pathname\n' >&2
    exit 1
  }
  rm "$fixture/.create-service.lock"
  assert_generated_service "$fixture" stable-two 8261 stable_two_db
  assert_no_transaction_artifacts "$fixture"
}

test_lock_cleanup_never_unlinks_replaced_path() {
  local fixture="$test_root/lock-cleanup-path-race"
  local hook_dir="$test_root/lock-cleanup-path-race-hook"
  local output="$test_root/lock-cleanup-path-race.log"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  printf 'opened-lock-inode\n' > "$fixture/.create-service.lock"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-lock-cleanup-unlink' \
      bash scripts/create-service.sh --name cleanup-lock --port 8262 --schema cleanup_lock_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/before-lock-cleanup-unlink.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'lock-cleanup RED: missing pre-unlink seam\n' >&2
    exit 1
  }
  mv "$fixture/.create-service.lock" "$test_root/opened-lock-inode"
  printf 'replacement-must-survive\n' > "$fixture/.create-service.lock"
  : > "$hook_dir/before-lock-cleanup-unlink.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'lock-cleanup generator failed unexpectedly: raw=%s output=%s\n' "$raw_exit" "$(cat "$output")" >&2
    exit 1
  }
  [[ -f "$fixture/.create-service.lock" \
    && "$(cat "$fixture/.create-service.lock")" == 'replacement-must-survive' ]] || {
    printf 'lock-cleanup RED: replacement pathname was unlinked\n' >&2
    exit 1
  }
  rm "$fixture/.create-service.lock"
  assert_generated_service "$fixture" cleanup-lock 8262 cleanup_lock_db
  assert_no_transaction_artifacts "$fixture"
}

test_target_publish_is_native_no_replace() {
  local fixture="$test_root/target-native-no-replace"
  local hook_dir="$test_root/target-native-no-replace-hook"
  local output="$test_root/target-native-no-replace.log"
  local pom_before="$test_root/target-native-pom.before"
  local catalog_before="$test_root/target-native-catalog.before"
  local target="$fixture/services/atomic-race-service"
  local third_party_inode
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  cp "$fixture/pom.xml" "$pom_before"
  cp "$fixture/architecture/service-catalog.yaml" "$catalog_before"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-target-native-publish' \
      bash scripts/create-service.sh --name atomic-race --port 8263 --schema atomic_race_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/before-target-native-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'target-no-replace RED: missing final native publication seam: %s\n' \
      "$(cat "$output")" >&2
    exit 1
  }
  if [[ -d "$target" ]]; then
    rmdir "$target"
  fi
  mkdir "$target"
  third_party_inode="$(inode_key "$target")"
  : > "$hook_dir/before-target-native-publish.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 \
    && -d "$target" \
    && "$(inode_key "$target")" == "$third_party_inode" \
    && -z "$(find "$target" -mindepth 1 -print -quit)" ]] || {
    printf 'target-no-replace RED: third-party empty target was replaced: raw=%s old=%s new=%s\n' \
      "$raw_exit" "$third_party_inode" "$([[ -d "$target" ]] && inode_key "$target" || printf missing)" >&2
    exit 1
  }
  assert_metadata_bytes "$fixture" "$pom_before" "$catalog_before"
  rmdir "$target"
  assert_no_transaction_artifacts "$fixture"
}

test_metadata_exchange_detects_final_compare_race() {
  local fixture="$test_root/metadata-final-compare-race"
  local hook_dir="$test_root/metadata-final-compare-race-hook"
  local output="$test_root/metadata-final-compare-race.log"
  local catalog_before="$test_root/metadata-final-catalog.before"
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  cp "$fixture/architecture/service-catalog.yaml" "$catalog_before"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-final-metadata-check-before-pom-publish' \
      bash scripts/create-service.sh --name compare-race --port 8264 --schema compare_race_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/after-final-metadata-check-before-pom-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'metadata-exchange RED: missing final compare seam\n' >&2
    exit 1
  }
  python3 - "$fixture/pom.xml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
path.write_text(path.read_text(encoding="utf-8").replace(
    "</project>", "  <!-- final-external-update -->\n</project>"
), encoding="utf-8")
PY
  : > "$hook_dir/after-final-metadata-check-before-pom-publish.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'metadata-exchange RED: final compare race silently committed\n' >&2
    exit 1
  }
  grep -F '<!-- final-external-update -->' "$fixture/pom.xml" >/dev/null || {
    printf 'metadata-exchange RED: final compare race lost external POM bytes\n' >&2
    exit 1
  }
  cmp -s "$fixture/architecture/service-catalog.yaml" "$catalog_before"
  test ! -e "$fixture/services/compare-race-service"
  assert_no_transaction_artifacts "$fixture"
}

test_metadata_rollback_preserves_post_exchange_updates() {
  local fixture
  local hook_dir
  local output
  local child
  local raw_exit

  fixture="$test_root/metadata-post-pom-race"
  hook_dir="$test_root/metadata-post-pom-race-hook"
  output="$test_root/metadata-post-pom-race.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-pom-publish' \
      bash scripts/create-service.sh --name post-pom --port 8265 --schema post_pom_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/after-pom-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'metadata-rollback RED: missing post-POM exchange seam\n' >&2
    exit 1
  }
  python3 - "$fixture/pom.xml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
path.write_text(path.read_text(encoding="utf-8").replace(
    "</project>", "  <!-- post-pom-external-update -->\n</project>"
), encoding="utf-8")
PY
  : > "$hook_dir/after-pom-publish.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'metadata-rollback RED: post-POM external update was silently accepted\n' >&2
    exit 1
  }
  grep -F '<!-- post-pom-external-update -->' "$fixture/pom.xml" >/dev/null
  test ! -e "$fixture/services/post-pom-service"
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 1

  fixture="$test_root/metadata-post-catalog-race"
  hook_dir="$test_root/metadata-post-catalog-race-hook"
  output="$test_root/metadata-post-catalog-race.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-catalog-publish' \
      bash scripts/create-service.sh --name post-catalog --port 8266 --schema post_catalog_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/after-catalog-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'metadata-rollback RED: missing post-catalog exchange seam\n' >&2
    exit 1
  }
  python3 - "$fixture/architecture/service-catalog.yaml" <<'PY'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
data["services"].append({
    "service": "external-service", "owner": "external-team", "schema": "external_db",
    "port": 62666, "publicApi": [], "internalApi": [], "eventsPublished": [],
    "eventsConsumed": [], "syncDependencies": []
})
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY
  : > "$hook_dir/after-catalog-publish.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'metadata-rollback RED: post-catalog external update was silently accepted\n' >&2
    exit 1
  }
  grep -F '"service": "external-service"' "$fixture/architecture/service-catalog.yaml" >/dev/null
  test ! -e "$fixture/services/post-catalog-service"
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 1
}

test_abrupt_exit_recovery_matrix() {
  local point
  local expected_stage
  local index=0
  local fixture
  local output
  local raw_exit
  for point in after-pom-publish after-catalog-publish after-target-publish after-journal-committed; do
    index=$((index + 1))
    fixture="$test_root/crash-recovery-$point"
    output="$test_root/crash-recovery-$point.log"
    make_fixture "$fixture"
    set +e
    (cd "$fixture" && \
      XZWL_CREATE_SERVICE_TEST_FAULTS="crash-$point" \
      bash scripts/create-service.sh --name "crash$index" --port "$((8266 + index))" \
        --schema "crash${index}_db") >"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -eq 97 ]] || {
      printf 'crash-recovery RED: fault %s did not stop at raw 97: raw=%s output=%s\n' \
        "$point" "$raw_exit" "$(cat "$output")" >&2
      exit 1
    }
    case "$point" in
      after-pom-publish) expected_stage=POM_EXCHANGED ;;
      after-catalog-publish) expected_stage=CATALOG_EXCHANGED ;;
      after-target-publish) expected_stage=PUBLISHING ;;
      after-journal-committed) expected_stage=COMMITTED ;;
    esac
    python3 - "$fixture" "$expected_stage" <<'PY'
from pathlib import Path
import json
import re
import sys

root = Path(sys.argv[1])
expected = sys.argv[2]
journals = list(root.glob(".create-service.*/RECOVERY.json"))
if len(journals) != 1:
    raise SystemExit(f"expected one durable recovery journal, found {len(journals)}")
data = json.loads(journals[0].read_text(encoding="utf-8"))
if data.get("stage") != expected:
    raise SystemExit(f"expected journal stage {expected}, got {data.get('stage')}")
for field in ("ownerToken", "pomOriginalSha256", "catalogOriginalSha256",
              "pomIntendedSha256", "catalogIntendedSha256"):
    if not isinstance(data.get(field), str) or re.fullmatch(r"[0-9a-f]{64}", data[field]) is None:
        raise SystemExit(f"journal {field} is not a durable identity/hash")
PY
    set +e
    (cd "$fixture" && bash scripts/create-service.sh \
      --name "crash$index" --port "$((8266 + index))" --schema "crash${index}_db") \
      >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -eq 0 ]] || {
      printf 'crash-recovery RED: restart did not recover %s: raw=%s output=%s\n' \
        "$point" "$raw_exit" "$(cat "$output")" >&2
      exit 1
    }
    assert_generated_service "$fixture" "crash$index" "$((8266 + index))" "crash${index}_db"
    assert_no_transaction_artifacts "$fixture"
  done
}

test_target_parent_fsync_precedes_commit() {
  local fixture="$test_root/target-parent-fsync-failure"
  local output="$test_root/target-parent-fsync-failure.log"
  local raw_exit
  make_fixture "$fixture"

  set +e
  (cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_FAULTS='target-parent-fsync-failure' \
    bash scripts/create-service.sh --name durable-target --port 8280 \
      --schema durable_target_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'target-parent-fsync RED: injected parent fsync failure was not observed\n' >&2
    return 1
  }
  grep -F 'target parent directory fsync failed' "$output" >/dev/null || {
    printf 'target-parent-fsync RED: missing explicit fsync diagnostic: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  test -d "$fixture/services/durable-target-service"
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 1
  python3 - "$fixture" <<'PY'
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
journals = list(root.glob(".create-service.*/RECOVERY.json"))
data = json.loads(journals[0].read_text(encoding="utf-8"))
if data.get("stage") != "PUBLISHING":
    raise SystemExit(f"expected recoverable PUBLISHING after fsync failure, got {data.get('stage')}")
PY
  printf 'third-party-after-fsync-failure\n' \
    > "$fixture/services/durable-target-service/third-party.txt"

  set +e
  (cd "$fixture" && bash scripts/create-service.sh \
    --name durable-target --port 8280 --schema durable_target_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'target-parent-fsync RED: restart did not finish visible commit: raw=%s output=%s\n' \
      "$raw_exit" "$(cat "$output")" >&2
    return 1
  }
  grep -Fx 'third-party-after-fsync-failure' \
    "$fixture/services/durable-target-service/third-party.txt" >/dev/null
  assert_generated_service "$fixture" durable-target 8280 durable_target_db
  assert_no_transaction_artifacts "$fixture"

  fixture="$test_root/target-parent-fsync-crash"
  local hook_dir="$test_root/target-parent-fsync-crash-hook"
  output="$test_root/target-parent-fsync-crash.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-target-parent-fsync-before-commit' \
      XZWL_CREATE_SERVICE_TEST_FAULTS='crash-after-target-parent-fsync' \
      bash scripts/create-service.sh --name durable-crash --port 8281 \
        --schema durable_crash_db
  ) >"$output" 2>&1 &
  local child=$!
  set -e
  wait_for_test_path "$hook_dir/after-target-parent-fsync-before-commit.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'target-parent-fsync RED: missing post-fsync/pre-COMMITTED ordering seam: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  python3 - "$fixture" <<'PY'
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
journals = list(root.glob(".create-service.*/RECOVERY.json"))
if len(journals) != 1:
    raise SystemExit(f"expected one journal before COMMITTED, found {len(journals)}")
data = json.loads(journals[0].read_text(encoding="utf-8"))
if data.get("stage") != "PUBLISHING":
    raise SystemExit(f"COMMITTED was recorded before target parent fsync seam: {data.get('stage')}")
if not (root / "services/durable-crash-service").is_dir():
    raise SystemExit("target was not visible after native publish and parent fsync")
PY
  printf 'third-party-after-parent-fsync\n' \
    > "$fixture/services/durable-crash-service/third-party.txt"
  : > "$hook_dir/after-target-parent-fsync-before-commit.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 97 ]] || {
    printf 'target-parent-fsync RED: post-fsync crash raw=%s output=%s\n' \
      "$raw_exit" "$(cat "$output")" >&2
    return 1
  }
  set +e
  (cd "$fixture" && bash scripts/create-service.sh \
    --name durable-crash --port 8281 --schema durable_crash_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'target-parent-fsync RED: post-fsync crash recovery raw=%s output=%s\n' \
      "$raw_exit" "$(cat "$output")" >&2
    return 1
  }
  grep -Fx 'third-party-after-parent-fsync' \
    "$fixture/services/durable-crash-service/third-party.txt" >/dev/null
  assert_generated_service "$fixture" durable-crash 8281 durable_crash_db
  assert_no_transaction_artifacts "$fixture"
}

test_target_parent_inode_is_bound_during_fsync() {
  local fixture="$test_root/target-parent-inode-binding"
  local hook_dir="$test_root/target-parent-inode-binding-hook"
  local output="$test_root/target-parent-inode-binding.log"
  local journal
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-target-ownership-before-parent-fsync' \
      bash scripts/create-service.sh --name parent-binding --port 8284 \
        --schema parent_binding_db
  ) >"$output" 2>&1 &
  local child=$!
  set -e
  wait_for_test_path "$hook_dir/after-target-ownership-before-parent-fsync.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'target-parent-binding RED: missing ownership-to-fsync seam: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  mv "$fixture/services" "$fixture/services-displaced-by-third-party"
  mkdir "$fixture/services"
  printf 'third-party-services-parent\n' > "$fixture/services/third-party.txt"
  : > "$hook_dir/after-target-ownership-before-parent-fsync.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'target-parent-binding RED: replaced services parent was committed\n' >&2
    return 1
  }
  grep -F 'services directory changed during target publication' "$output" >/dev/null || {
    printf 'target-parent-binding RED: missing fail-closed diagnostic: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  grep -Fx 'third-party-services-parent' "$fixture/services/third-party.txt" >/dev/null
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 1

  set +e
  (cd "$fixture" && bash scripts/create-service.sh \
    --name parent-binding --port 8284 --schema parent_binding_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'target-parent-binding RED: restart crossed a changed services parent\n' >&2
    return 1
  }
  grep -F 'published target parent differs from transaction journal' "$output" >/dev/null
  grep -Fx 'third-party-services-parent' "$fixture/services/third-party.txt" >/dev/null
  test -d "$fixture/services-displaced-by-third-party/parent-binding-service"
  journal="$(pending_journal_path "$fixture")"
  assert_journal_stage "$journal" RECOVERY_REQUIRED

  fixture="$test_root/target-parent-prepublish-binding"
  hook_dir="$test_root/target-parent-prepublish-binding-hook"
  output="$test_root/target-parent-prepublish-binding.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-target-native-publish' \
      bash scripts/create-service.sh --name prepublish-binding --port 8288 \
        --schema prepublish_binding_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/before-target-native-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'target-parent-binding RED: missing prepublish seam\n' >&2
    return 1
  }
  mv "$fixture/services" "$fixture/services-prepublish-displaced"
  mkdir "$fixture/services"
  printf 'third-party-prepublish-parent\n' > "$fixture/services/third-party.txt"
  : > "$hook_dir/before-target-native-publish.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]]
  test ! -e "$fixture/services/prepublish-binding-service"
  test -d "$fixture/services-prepublish-displaced/prepublish-binding-service"
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 1

  set +e
  (cd "$fixture" && bash scripts/create-service.sh \
    --name prepublish-binding --port 8288 --schema prepublish_binding_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'target-parent-binding RED: recovery committed a target published under a replaced parent\n' >&2
    return 1
  }
  grep -F 'published target parent differs from transaction journal' "$output" >/dev/null || {
    printf 'target-parent-binding RED: missing recovery parent conflict diagnostic: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  grep -Fx 'third-party-prepublish-parent' "$fixture/services/third-party.txt" >/dev/null
  test ! -e "$fixture/services/prepublish-binding-service"
  test -d "$fixture/services-prepublish-displaced/prepublish-binding-service"
  journal="$(pending_journal_path "$fixture")"
  assert_journal_stage "$journal" RECOVERY_REQUIRED
}

test_native_filesystem_probe_precedes_recovery_and_staging() {
  local error_name
  for error_name in ENOTSUP EOPNOTSUPP EINVAL; do
    local error_slug
    error_slug="$(printf '%s' "$error_name" | tr '[:upper:]' '[:lower:]')"
    local fixture="$test_root/native-probe-$error_slug"
    local hook_dir="$test_root/native-probe-$error_slug-hook"
    local modules="$test_root/native-probe-$error_slug-modules"
    local output="$test_root/native-probe-$error_slug.log"
    local before
    local raw_exit
    make_fixture "$fixture"
    mkdir -p "$hook_dir" "$modules"
    cat > "$modules/sitecustomize.py" <<'PY'
import ctypes
import errno
import os
import sys

_real_cdll = ctypes.CDLL


class _UnsupportedNativeCall:
    def __init__(self, name):
        self.name = name
        self.argtypes = None
        self.restype = None

    def __call__(self, *args):
        marker = os.environ.get("XZWL_NATIVE_CALL_MARKER")
        if marker:
            with open(marker, "a", encoding="utf-8") as stream:
                stream.write(self.name + "\n")
        ctypes.set_errno(getattr(errno, os.environ["XZWL_NATIVE_PROBE_ERRNO"]))
        return -1


class _LibraryProxy:
    def __init__(self, library):
        self._library = library
        self.renameat2 = _UnsupportedNativeCall("renameat2")
        self.renameatx_np = _UnsupportedNativeCall("renameatx_np")
        self.renamex_np = _UnsupportedNativeCall("renamex_np")

    def __getattr__(self, name):
        return getattr(self._library, name)


def _cdll(*args, **kwargs):
    return _LibraryProxy(_real_cdll(*args, **kwargs))


ctypes.CDLL = _cdll
PY
    before="$(snapshot_state "$fixture")"
    set +e
    (
      cd "$fixture"
      exec env \
        PYTHONPATH="$modules" \
        XZWL_NATIVE_PROBE_ERRNO="$error_name" \
        XZWL_NATIVE_CALL_MARKER="$hook_dir/native-called" \
        XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
        XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-metadata-compare' \
        bash scripts/create-service.sh --name "probe-$error_slug" \
          --port 8282 --schema "probe_${error_slug}_db"
    ) >"$output" 2>&1 &
    local child=$!
    set -e

    if wait_for_test_path "$hook_dir/before-metadata-compare.ready" "$child"; then
      : > "$hook_dir/before-metadata-compare.continue"
      wait "$child" 2>/dev/null || true
      printf 'native-probe RED: %s was first exercised only after staging/journal creation\n' \
        "$error_name" >&2
      return 1
    fi
    set +e
    wait "$child"; raw_exit=$?
    set -e
    [[ "$raw_exit" -ne 0 ]] || {
      printf 'native-probe RED: %s unsupported semantics were accepted\n' "$error_name" >&2
      return 1
    }
    grep -F 'native filesystem capability probe failed' "$output" >/dev/null || {
      printf 'native-probe RED: %s missing fail-fast diagnostic: %s\n' \
        "$error_name" "$(cat "$output")" >&2
      return 1
    }
    test -s "$hook_dir/native-called"
    [[ "$before" == "$(snapshot_state "$fixture")" ]] || {
      printf 'native-probe RED: %s changed repository state\n' "$error_name" >&2
      return 1
    }
    test ! -e "$fixture/services/probe-$error_slug-service"
    assert_no_transaction_artifacts "$fixture"
  done

  local fixture="$test_root/native-probe-before-pending-recovery"
  local output="$test_root/native-probe-before-pending-recovery.log"
  local before
  local raw_exit
  make_fixture "$fixture"
  set +e
  (cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_FAULTS='crash-after-pom-publish' \
    bash scripts/create-service.sh --name pending-probe --port 8287 \
      --schema pending_probe_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 97 ]]
  local pending_journal
  pending_journal="$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f -print -quit)"
  test -n "$pending_journal"
  cp "$pending_journal" "$test_root/pending-probe-journal.before"
  before="$(snapshot_state "$fixture")"
  set +e
  (cd "$fixture" && \
    PYTHONPATH="$modules" \
    XZWL_NATIVE_PROBE_ERRNO='ENOTSUP' \
    XZWL_NATIVE_CALL_MARKER="$test_root/pending-probe-native-called" \
    bash scripts/create-service.sh --name pending-probe --port 8287 \
      --schema pending_probe_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]]
  grep -F 'native filesystem capability probe failed' "$output" >/dev/null
  test -s "$test_root/pending-probe-native-called"
  [[ "$before" == "$(snapshot_state "$fixture")" ]]
  cmp -s "$pending_journal" "$test_root/pending-probe-journal.before" || {
    printf 'native-probe RED: pending journal changed before capability probe passed\n' >&2
    return 1
  }

  fixture="$test_root/native-probe-operation-error-cleanup-race"
  local hook_dir="$test_root/native-probe-operation-error-cleanup-race-hook"
  output="$test_root/native-probe-operation-error-cleanup-race.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      PYTHONPATH="$modules" \
      XZWL_NATIVE_PROBE_ERRNO='ENOTSUP' \
      XZWL_NATIVE_CALL_MARKER="$hook_dir/native-called" \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-native-probe-fd-cleanup' \
      bash scripts/create-service.sh --name probe-error-race --port 8289 \
        --schema probe_error_race_db
  ) >"$output" 2>&1 &
  local child=$!
  set -e
  wait_for_test_path "$hook_dir/before-native-probe-fd-cleanup.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'native-probe RED: operation-error cleanup lacks fd-bound seam\n' >&2
    return 1
  }
  local probe_path
  probe_path="$(native_probe_path "$fixture")"
  test -n "$probe_path"
  mv "$probe_path" "$(dirname "$probe_path")/probe-operation-error-owned-aside"
  mkdir "$probe_path"
  printf 'third-party-operation-error-probe\n' > "$probe_path/third-party.txt"
  : > "$hook_dir/before-native-probe-fd-cleanup.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]]
  grep -F 'native filesystem capability probe failed' "$output" >/dev/null
  grep -F 'native probe cleanup failed' "$output" >/dev/null
  grep -Fx 'third-party-operation-error-probe' "$probe_path/third-party.txt" >/dev/null || {
    printf 'native-probe RED: operation-error cleanup deleted third-party bytes\n' >&2
    return 1
  }
  test ! -e "$fixture/services/probe-error-race-service"
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 0

  fixture="$test_root/native-probe-cleanup-failure"
  output="$test_root/native-probe-cleanup-failure.log"
  make_fixture "$fixture"
  before="$(snapshot_state "$fixture")"
  set +e
  (cd "$fixture" && \
    XZWL_CREATE_SERVICE_TEST_FAULTS='native-probe-cleanup-failure' \
    bash scripts/create-service.sh --name probe-cleanup --port 8283 \
      --schema probe_cleanup_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'native-probe RED: cleanup failure did not stop before the main transaction\n' >&2
    return 1
  }
  grep -F 'native filesystem capability probe cleanup failed' "$output" >/dev/null || {
    printf 'native-probe RED: cleanup failure lacked retained-evidence diagnostic: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  [[ "$before" == "$(snapshot_state "$fixture")" ]]
  test ! -e "$fixture/services/probe-cleanup-service"
  test "$(find "$(stable_holding_root "$fixture")" -maxdepth 1 \
    -name '.create-service.native-probe-*' -type d | wc -l | tr -d ' ')" -eq 1
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 0

  fixture="$test_root/native-probe-cleanup-race"
  local hook_dir="$test_root/native-probe-cleanup-race-hook"
  output="$test_root/native-probe-cleanup-race.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-native-probe-fd-cleanup' \
      bash scripts/create-service.sh --name probe-race --port 8285 --schema probe_race_db
  ) >"$output" 2>&1 &
  local child=$!
  set -e
  wait_for_test_path "$hook_dir/before-native-probe-fd-cleanup.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'native-probe RED: missing cleanup ownership-claim seam: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  local probe_path
  probe_path="$(native_probe_path "$fixture")"
  test -n "$probe_path"
  mv "$probe_path" "$(dirname "$probe_path")/probe-owned-aside"
  mkdir "$probe_path"
  printf 'third-party-probe-directory\n' > "$probe_path/third-party.txt"
  : > "$hook_dir/before-native-probe-fd-cleanup.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'native-probe RED: replaced cleanup path was accepted\n' >&2
    return 1
  }
  grep -F 'native filesystem capability probe cleanup failed' "$output" >/dev/null
  grep -Fx 'third-party-probe-directory' "$probe_path/third-party.txt" >/dev/null || {
    printf 'native-probe RED: cleanup deleted the replacement third-party directory\n' >&2
    return 1
  }
  test ! -e "$fixture/services/probe-race-service"
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 0

  fixture="$test_root/native-probe-cross-filesystem"
  hook_dir="$test_root/native-probe-cross-filesystem-hook"
  local modules="$test_root/native-probe-cross-filesystem-modules"
  output="$test_root/native-probe-cross-filesystem.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir" "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import os
from pathlib import Path

_real_lstat = os.lstat
_real_path_lstat = Path.lstat


def _cross_filesystem_lstat(path, *args, **kwargs):
    result = _real_lstat(path, *args, **kwargs)
    text = os.fspath(path)
    if text.endswith("/architecture"):
        values = list(result)
        values[2] = result.st_dev + 1
        return os.stat_result(values)
    return result


def _cross_filesystem_path_lstat(path):
    result = _real_path_lstat(path)
    if path.name == "architecture":
        values = list(result)
        values[2] = result.st_dev + 1
        return os.stat_result(values)
    return result


os.lstat = _cross_filesystem_lstat
Path.lstat = _cross_filesystem_path_lstat
PY
  set +e
  (
    cd "$fixture"
    exec env \
      PYTHONPATH="$modules" \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='before-metadata-compare' \
      bash scripts/create-service.sh --name cross-fs --port 8286 --schema cross_fs_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  if wait_for_test_path "$hook_dir/before-metadata-compare.ready" "$child"; then
    : > "$hook_dir/before-metadata-compare.continue"
    wait "$child" 2>/dev/null || true
    printf 'native-probe RED: cross-filesystem transaction reached staging\n' >&2
    return 1
  fi
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'native-probe RED: cross-filesystem transaction was accepted\n' >&2
    return 1
  }
  grep -F 'must be on the repository filesystem' "$output" >/dev/null || {
    printf 'native-probe RED: cross-filesystem failure lacked diagnostic: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  test ! -e "$fixture/services/cross-fs-service"
  assert_no_transaction_artifacts "$fixture"
}

test_native_probe_uses_crash_recoverable_stable_root() {
  local modules="$test_root/native-probe-crash-modules"
  local fixture outer holding_root output marker raw_exit child phase hook_dir before
  local crash_pythonpath service_slug crash_hook_points
  local failures=0
  mkdir -p "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import os
import time
from pathlib import Path

phase = os.environ.get("XZWL_PROBE_CRASH_PHASE", "")
ready = Path(os.environ.get("XZWL_PROBE_CRASH_READY", "/nonexistent"))
mutation = os.environ.get("XZWL_REPO_PROBE_MUTATION_MARKER", "")
repo = os.environ.get("XZWL_REPO_PROBE_ROOT", "")
real_mkdir = os.mkdir
real_open = os.open
real_close = os.close
tracked_fd = None


def is_probe_name(value):
    name = os.fsdecode(value)
    return name.startswith(".create-service.probe.") or name.startswith(
        ".create-service.native-probe-"
    )


def pause():
    ready.write_text("ready\n", encoding="utf-8")
    while True:
        time.sleep(1)


def guarded_mkdir(path, mode=0o777, *, dir_fd=None):
    result = real_mkdir(path, mode, dir_fd=dir_fd)
    if is_probe_name(path):
        if mutation and repo:
            try:
                opened = os.fstat(dir_fd) if dir_fd is not None else None
                visible = os.stat(repo)
            except Exception:
                opened = visible = None
            if opened is not None and (opened.st_dev, opened.st_ino) == (
                visible.st_dev, visible.st_ino
            ):
                Path(mutation).write_text("repo probe directory created\n", encoding="utf-8")
        if phase == "create":
            pause()
    return result


def guarded_open(path, flags, mode=0o777, *, dir_fd=None):
    global tracked_fd
    descriptor = real_open(path, flags, mode, dir_fd=dir_fd)
    if phase == "files" and os.fsdecode(path) == "exchange-second" and flags & os.O_CREAT:
        tracked_fd = descriptor
    return descriptor


def guarded_close(descriptor):
    global tracked_fd
    should_pause = phase == "files" and descriptor == tracked_fd
    result = real_close(descriptor)
    if should_pause:
        tracked_fd = None
        pause()
    return result


os.mkdir = guarded_mkdir
os.open = guarded_open
os.close = guarded_close
PY

  outer="$test_root/native-probe-root-unavailable"
  fixture="$outer/repository"
  output="$test_root/native-probe-root-unavailable.log"
  marker="$test_root/native-probe-root-unavailable.mutated"
  mkdir -p "$outer"
  make_fixture "$fixture"
  before="$(snapshot_state "$fixture")"
  chmod 0555 "$outer"
  set +e
  (
    cd "$fixture"
    exec env PYTHONPATH="$modules" \
      XZWL_REPO_PROBE_MUTATION_MARKER="$marker" \
      XZWL_REPO_PROBE_ROOT="$fixture" \
      XZWL_CREATE_SERVICE_TEST_FAULTS='stable-root-parent-unwritable' \
      bash scripts/create-service.sh --name probe-root-unavailable --port 8350 \
        --schema probe_root_unavailable_db
  ) >"$output" 2>&1
  raw_exit=$?
  set -e
  chmod 0755 "$outer"
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'native-probe stable-root RED: unavailable root was accepted\n' >&2
    failures=$((failures + 1))
  }
  grep -F 'stable holding root' "$output" >/dev/null || {
    printf 'native-probe stable-root RED: unavailable root lacked preflight diagnostic: %s\n' \
      "$(cat "$output")" >&2
    failures=$((failures + 1))
  }
  if [[ -e "$marker" ]]; then
    printf 'native-probe stable-root RED: repo probe mutated before root preflight (raw=%s)\n' \
      "$raw_exit" >&2
    failures=$((failures + 1))
  fi
  [[ "$before" == "$(snapshot_state "$fixture")" ]] || {
    printf 'native-probe stable-root RED: unavailable root changed repository bytes\n' >&2
    failures=$((failures + 1))
  }
  assert_no_transaction_artifacts "$fixture"

  outer="$test_root/native-probe-existing-root-unwritable-parent"
  fixture="$outer/repository"
  output="$test_root/native-probe-existing-root-unwritable-parent.log"
  mkdir -p "$outer"
  make_fixture "$fixture"
  holding_root="$(stable_holding_root "$fixture")"
  mkdir -m 0700 "$holding_root"
  chmod 0555 "$outer"
  set +e
  (cd "$fixture" && bash scripts/create-service.sh --name existing-root --port 8351 \
    --schema existing_root_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  chmod 0755 "$outer"
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'native-probe stable-root RED: existing valid root required writable parent: %s\n' \
      "$(cat "$output")" >&2
    failures=$((failures + 1))
  }
  [[ -d "$fixture/services/existing-root-service" ]] || failures=$((failures + 1))
  assert_no_transaction_artifacts "$fixture"

  fixture="$test_root/native-probe-unknown-stable-child"
  output="$test_root/native-probe-unknown-stable-child.log"
  make_fixture "$fixture"
  holding_root="$(stable_holding_root "$fixture")"
  mkdir -m 0700 "$holding_root" "$holding_root/third-party-child"
  printf 'third-party-stable-root-data\n' > "$holding_root/third-party-child/data.txt"
  set +e
  (cd "$fixture" && bash scripts/create-service.sh --name unknown-child --port 8353 \
    --schema unknown_child_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'native-probe stable-root RED: unknown stable-root child was accepted\n' >&2
    failures=$((failures + 1))
  }
  grep -F 'unexpected stable holding artifact' "$output" >/dev/null || {
    printf 'native-probe stable-root RED: unknown child lacked fail-closed diagnostic\n' >&2
    failures=$((failures + 1))
  }
  grep -Fx 'third-party-stable-root-data' \
    "$holding_root/third-party-child/data.txt" >/dev/null || {
    printf 'native-probe stable-root RED: unknown child bytes were changed\n' >&2
    failures=$((failures + 1))
  }

  fixture="$test_root/native-probe-unclaimed-lookalike"
  output="$test_root/native-probe-unclaimed-lookalike.log"
  make_fixture "$fixture"
  holding_root="$(stable_holding_root "$fixture")"
  mkdir -m 0700 "$holding_root"
  local lookalike="$holding_root/.create-service.native-probe-00000000000000000000000000000000"
  mkdir -m 0700 "$lookalike"
  local lookalike_identity
  lookalike_identity="$(python3 - "$lookalike" <<'PY'
from pathlib import Path
import sys
info = Path(sys.argv[1]).stat()
print(f"{info.st_dev}:{info.st_ino}:{info.st_mode}")
PY
)"
  set +e
  (cd "$fixture" && bash scripts/create-service.sh --name unclaimed-probe --port 8354 \
    --schema unclaimed_probe_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'native-probe stable-root RED: unclaimed lookalike was deleted or accepted\n' >&2
    failures=$((failures + 1))
  }
  grep -F 'unclaimed native filesystem probe was retained' "$output" >/dev/null || {
    printf 'native-probe stable-root RED: unclaimed lookalike lacked diagnostic\n' >&2
    failures=$((failures + 1))
  }
  [[ "$lookalike_identity" == "$(python3 - "$lookalike" <<'PY'
from pathlib import Path
import sys
info = Path(sys.argv[1]).stat()
print(f"{info.st_dev}:{info.st_ino}:{info.st_mode}")
PY
)" ]] || {
    printf 'native-probe stable-root RED: unclaimed lookalike identity changed\n' >&2
    failures=$((failures + 1))
  }

  for phase in create files native cleanup; do
    fixture="$test_root/native-probe-sigkill-$phase"
    output="$test_root/native-probe-sigkill-$phase.log"
    marker="$test_root/native-probe-sigkill-$phase.ready"
    hook_dir="$test_root/native-probe-sigkill-$phase-hook"
    make_fixture "$fixture"
    holding_root="$(stable_holding_root "$fixture")"
    mkdir -m 0700 "$holding_root" "$hook_dir"
    crash_pythonpath=""
    crash_hook_points=""
    service_slug="$phase"
    [[ "$phase" == native ]] && service_slug="operation"
    if [[ "$phase" == create || "$phase" == files ]]; then
      crash_pythonpath="$modules"
    elif [[ "$phase" == native ]]; then
      crash_hook_points="after-native-probe-exchange"
    else
      crash_hook_points="before-native-probe-fd-cleanup"
    fi
    set +e
    (
      cd "$fixture"
      exec env PYTHONPATH="$crash_pythonpath" \
        XZWL_PROBE_CRASH_PHASE="$phase" \
        XZWL_PROBE_CRASH_READY="$marker" \
        XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
        XZWL_CREATE_SERVICE_TEST_HOOK_POINTS="$crash_hook_points" \
        bash scripts/create-service.sh --name "probe-crash-$service_slug" --port 8352 \
          --schema "probe_crash_${service_slug}_db"
    ) >"$output" 2>&1 &
    child=$!
    set -e
    if [[ "$phase" == cleanup ]]; then
      wait_for_test_path "$hook_dir/before-native-probe-fd-cleanup.ready" "$child" || {
        wait "$child" 2>/dev/null || true
        printf 'native-probe stable-root RED: cleanup SIGKILL seam was not reached: %s\n' \
          "$(cat "$output")" >&2
        failures=$((failures + 1))
        continue
      }
    elif [[ "$phase" == native ]]; then
      wait_for_test_path "$hook_dir/after-native-probe-exchange.ready" "$child" || {
        wait "$child" 2>/dev/null || true
        printf 'native-probe stable-root RED: native SIGKILL seam was not reached: %s\n' \
          "$(cat "$output")" >&2
        failures=$((failures + 1))
        continue
      }
    else
      wait_for_test_path "$marker" "$child" || {
        wait "$child" 2>/dev/null || true
        printf 'native-probe stable-root RED: %s SIGKILL phase was not reached\n' "$phase" >&2
        failures=$((failures + 1))
        continue
      }
    fi
    kill -KILL "$child"
    set +e
    wait "$child"; raw_exit=$?
    set -e
    [[ "$raw_exit" -ne 0 ]] || {
      printf 'native-probe stable-root RED: %s SIGKILL returned zero\n' "$phase" >&2
      failures=$((failures + 1))
    }
    if [[ "$phase" == create ]]; then
      set +e
      (cd "$fixture" && bash scripts/create-service.sh --name "probe-crash-$service_slug" \
        --port 8352 --schema "probe_crash_${service_slug}_db") >>"$output" 2>&1
      raw_exit=$?
      set -e
      if [[ "$raw_exit" -eq 0 ]]; then
        printf 'native-probe stable-root RED: markerless create residue was trusted on retry\n' >&2
        failures=$((failures + 1))
        continue
      fi
      python3 - "$holding_root" <<'PY'
from pathlib import Path
import os
import sys

root = Path(sys.argv[1])
directories = [
    path for path in root.iterdir()
    if path.name.startswith(".create-service.native-probe-")
    and not path.name.endswith(".claim")
]
if len(directories) != 1:
    raise SystemExit("expected one markerless native probe directory after create crash")
directory = directories[0]
if list(directory.iterdir()):
    raise SystemExit("create-crash native probe directory was not markerless and empty")
claim = root / f"{directory.name}.claim"
if not claim.is_file() or claim.is_symlink():
    raise SystemExit("create-crash native probe claim was not retained")
directory.rmdir()
root_fd = os.open(root, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
try:
    os.fsync(root_fd)
finally:
    os.close(root_fd)
PY
    fi
    set +e
    (cd "$fixture" && bash scripts/create-service.sh --name "probe-crash-$service_slug" \
      --port 8352 --schema "probe_crash_${service_slug}_db") >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -eq 0 ]] || {
      printf 'native-probe stable-root RED: %s crash residue blocked retry raw=%s: %s\n' \
        "$phase" "$raw_exit" "$(cat "$output")" >&2
      failures=$((failures + 1))
      continue
    }
    assert_generated_service "$fixture" "probe-crash-$service_slug" 8352 \
      "probe_crash_${service_slug}_db"
    test -z "$(find "$fixture" -maxdepth 1 -name '.create-service.probe.*' -print -quit)" || {
      printf 'native-probe stable-root RED: %s retained a repo-root probe\n' "$phase" >&2
      failures=$((failures + 1))
    }
    assert_no_transaction_artifacts "$fixture"
  done
  [[ "$failures" -eq 0 ]] || return 1
}

test_native_probe_creation_preserves_unowned_directory() {
  local fixture="$test_root/native-probe-creation-ownership-race"
  local modules="$test_root/native-probe-creation-ownership-race-modules"
  local state="$test_root/native-probe-creation-ownership-race.json"
  local output="$test_root/native-probe-creation-ownership-race.log"
  local holding_root raw_exit retry_raw
  make_fixture "$fixture"
  mkdir -p "$modules"
  holding_root="$(stable_holding_root "$fixture")"
  mkdir -m 0700 "$holding_root"
  cat > "$modules/sitecustomize.py" <<'PY'
import json
import os
from pathlib import Path

real_mkdir = os.mkdir
injected = False
state = Path(os.environ["XZWL_PROBE_OWNERSHIP_STATE"])


def race_mkdir(path, mode=0o777, *, dir_fd=None):
    global injected
    name = os.fsdecode(path)
    if (
        not injected
        and dir_fd is not None
        and name.startswith(".create-service.native-probe-")
        and not name.endswith(".claim")
    ):
        injected = True
        real_mkdir(path, 0o700, dir_fd=dir_fd)
        directory_fd = os.open(
            path, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY, dir_fd=dir_fd,
        )
        try:
            file_fd = os.open(
                "third-party.txt",
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=directory_fd,
            )
            try:
                os.write(file_fd, b"third-party-probe-directory\n")
            finally:
                os.close(file_fd)
        finally:
            os.close(directory_fd)
        info = os.stat(path, dir_fd=dir_fd, follow_symlinks=False)
        state.write_text(json.dumps({
            "name": name,
            "device": info.st_dev,
            "inode": info.st_ino,
            "mode": info.st_mode,
        }), encoding="utf-8")
    return real_mkdir(path, mode, dir_fd=dir_fd)


os.mkdir = race_mkdir
PY

  set +e
  (
    cd "$fixture"
    exec env PYTHONPATH="$modules" XZWL_PROBE_OWNERSHIP_STATE="$state" \
      bash scripts/create-service.sh --name ownership-race --port 8420 \
        --schema ownership_race_db
  ) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 && -s "$state" ]] || {
    printf 'native-probe ownership RED: mkdir race was not observed, raw=%s: %s\n' \
      "$raw_exit" "$(cat "$output")" >&2
    return 1
  }
  python3 - "$holding_root" "$state" <<'PY'
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
state = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
directory = root / state["name"]
if not directory.exists():
    raise SystemExit(
        "native-probe ownership RED: third-party directory was deleted"
    )
info = directory.lstat()
if (info.st_dev, info.st_ino, info.st_mode) != (
    state["device"], state["inode"], state["mode"],
):
    raise SystemExit("third-party probe directory identity changed")
if (directory / "third-party.txt").read_bytes() != b"third-party-probe-directory\n":
    raise SystemExit("third-party probe directory bytes changed")
claim = root / f"{state['name']}.claim"
if not claim.is_file() or claim.is_symlink():
    raise SystemExit("owned claim evidence was not retained")
PY

  set +e
  (cd "$fixture" && bash scripts/create-service.sh --name ownership-race --port 8420 \
    --schema ownership_race_db) >>"$output" 2>&1
  retry_raw=$?
  set -e
  [[ "$retry_raw" -ne 0 ]] || {
    printf 'native-probe ownership RED: unresolved third-party conflict did not fail closed\n' >&2
    return 1
  }
  python3 - "$holding_root" "$state" <<'PY'
from pathlib import Path
import json
import os
import sys

root = Path(sys.argv[1])
state = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
directory = root / state["name"]
info = directory.lstat()
if (info.st_dev, info.st_ino, info.st_mode) != (
    state["device"], state["inode"], state["mode"],
):
    raise SystemExit("retry changed third-party directory identity")
file_path = directory / "third-party.txt"
if file_path.read_bytes() != b"third-party-probe-directory\n":
    raise SystemExit("retry changed third-party bytes")
file_path.unlink()
directory.rmdir()
root_fd = os.open(root, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
try:
    os.fsync(root_fd)
finally:
    os.close(root_fd)
PY
  (cd "$fixture" && bash scripts/create-service.sh --name ownership-race --port 8420 \
    --schema ownership_race_db) >>"$output" 2>&1
  assert_generated_service "$fixture" ownership-race 8420 ownership_race_db
  assert_no_transaction_artifacts "$fixture"
}

test_native_probe_recovery_preserves_empty_markerless_directory() {
  local fixture="$test_root/native-probe-empty-ownership-race"
  local modules="$test_root/native-probe-empty-ownership-race-modules"
  local state="$test_root/native-probe-empty-ownership-race.json"
  local output="$test_root/native-probe-empty-ownership-race.log"
  local holding_root raw_exit retry_raw
  make_fixture "$fixture"
  mkdir -p "$modules"
  holding_root="$(stable_holding_root "$fixture")"
  mkdir -m 0700 "$holding_root"
  cat > "$modules/sitecustomize.py" <<'PY'
import json
import os
from pathlib import Path

real_mkdir = os.mkdir
injected = False
state = Path(os.environ["XZWL_PROBE_EMPTY_OWNERSHIP_STATE"])


def race_mkdir(path, mode=0o777, *, dir_fd=None):
    global injected
    name = os.fsdecode(path)
    if (
        not injected
        and dir_fd is not None
        and name.startswith(".create-service.native-probe-")
        and not name.endswith(".claim")
    ):
        injected = True
        real_mkdir(path, 0o700, dir_fd=dir_fd)
        info = os.stat(path, dir_fd=dir_fd, follow_symlinks=False)
        state.write_text(json.dumps({
            "name": name,
            "device": info.st_dev,
            "inode": info.st_ino,
            "mode": info.st_mode,
        }), encoding="utf-8")
    return real_mkdir(path, mode, dir_fd=dir_fd)


os.mkdir = race_mkdir
PY

  set +e
  (
    cd "$fixture"
    exec env PYTHONPATH="$modules" XZWL_PROBE_EMPTY_OWNERSHIP_STATE="$state" \
      bash scripts/create-service.sh --name empty-ownership-race --port 8422 \
        --schema empty_ownership_race_db
  ) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 && -s "$state" ]] || {
    printf 'native-probe empty ownership RED: mkdir race was not observed, raw=%s: %s\n' \
      "$raw_exit" "$(cat "$output")" >&2
    return 1
  }
  python3 - "$holding_root" "$state" <<'PY'
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
state_path = Path(sys.argv[2])
state = json.loads(state_path.read_text(encoding="utf-8"))
directory = root / state["name"]
info = directory.lstat()
if (info.st_dev, info.st_ino, info.st_mode) != (
    state["device"], state["inode"], state["mode"],
):
    raise SystemExit("empty third-party probe directory identity changed")
if list(directory.iterdir()):
    raise SystemExit("empty third-party probe directory gained entries")
claim = root / f"{state['name']}.claim"
claim_info = claim.lstat()
if not claim.is_file() or claim.is_symlink():
    raise SystemExit("owned claim evidence was not retained")
state.update({
    "claimDevice": claim_info.st_dev,
    "claimInode": claim_info.st_ino,
    "claimMode": claim_info.st_mode,
})
state_path.write_text(json.dumps(state), encoding="utf-8")
PY

  set +e
  (cd "$fixture" && bash scripts/create-service.sh --name empty-ownership-race \
    --port 8422 --schema empty_ownership_race_db) >>"$output" 2>&1
  retry_raw=$?
  set -e
  [[ "$retry_raw" -ne 0 ]] || {
    printf 'native-probe empty ownership RED: markerless empty directory was accepted on retry\n' >&2
    return 1
  }
  python3 - "$holding_root" "$state" <<'PY'
from pathlib import Path
import json
import os
import sys

root = Path(sys.argv[1])
state = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
directory = root / state["name"]
info = directory.lstat()
if (info.st_dev, info.st_ino, info.st_mode) != (
    state["device"], state["inode"], state["mode"],
):
    raise SystemExit("retry changed empty third-party directory identity")
if list(directory.iterdir()):
    raise SystemExit("retry changed empty third-party directory contents")
claim = root / f"{state['name']}.claim"
claim_info = claim.lstat()
if (claim_info.st_dev, claim_info.st_ino, claim_info.st_mode) != (
    state["claimDevice"], state["claimInode"], state["claimMode"],
):
    raise SystemExit("retry changed owned claim identity")
directory.rmdir()
root_fd = os.open(root, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
try:
    os.fsync(root_fd)
finally:
    os.close(root_fd)
PY

  (cd "$fixture" && bash scripts/create-service.sh --name empty-ownership-race \
    --port 8422 --schema empty_ownership_race_db) >>"$output" 2>&1
  assert_generated_service "$fixture" empty-ownership-race 8422 empty_ownership_race_db
  test -z "$(find "$holding_root" -maxdepth 1 \
    -name '.create-service.native-probe-*' -print -quit)" || {
    printf 'native-probe empty ownership RED: successful retry retained probe artifacts\n' >&2
    return 1
  }
  assert_no_transaction_artifacts "$fixture"
}

test_native_probe_cleanup_atomically_preserves_replacements() {
  local modules="$test_root/native-probe-cleanup-atomic-modules"
  local fixture state output holding_root raw_exit retry_raw kind
  mkdir -p "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import ctypes
import json
import os
import stat
from pathlib import Path

real_cdll = ctypes.CDLL
real_unlink = os.unlink
real_rmdir = os.rmdir
injected = False
kind = os.environ["XZWL_PROBE_CLEANUP_RACE_KIND"]
state = Path(os.environ["XZWL_PROBE_CLEANUP_RACE_STATE"])


def text(value):
    return os.fsdecode(value)


def matches(name):
    if kind == "entry":
        return name == "exchange-first"
    if kind == "marker":
        return name == ".NATIVE_PROBE"
    if kind == "directory":
        return name.startswith(".create-service.native-probe-") and not name.endswith(".claim")
    if kind == "claim":
        return name.startswith(".create-service.native-probe-") and name.endswith(".claim")
    return False


def inject(parent_fd, name):
    global injected
    if injected or parent_fd is None or not matches(name):
        return
    injected = True
    aside = f"{name}.owned-aside-{kind}"
    os.rename(name, aside, src_dir_fd=parent_fd, dst_dir_fd=parent_fd)
    original = os.stat(aside, dir_fd=parent_fd, follow_symlinks=False)
    if stat.S_ISDIR(original.st_mode):
        os.mkdir(name, 0o700, dir_fd=parent_fd)
        replacement_fd = os.open(
            name, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY, dir_fd=parent_fd,
        )
        try:
            data_fd = os.open(
                "third-party.txt",
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=replacement_fd,
            )
            try:
                os.write(data_fd, b"third-party-native-probe-race\n")
            finally:
                os.close(data_fd)
        finally:
            os.close(replacement_fd)
    else:
        replacement_fd = os.open(
            name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=parent_fd,
        )
        try:
            os.write(replacement_fd, b"third-party-native-probe-race\n")
        finally:
            os.close(replacement_fd)
    replacement = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    state.write_text(json.dumps({
        "kind": kind,
        "name": name,
        "aside": aside,
        "device": replacement.st_dev,
        "inode": replacement.st_ino,
        "mode": replacement.st_mode,
    }), encoding="utf-8")


def race_unlink(path, *, dir_fd=None):
    inject(dir_fd, text(path))
    return real_unlink(path, dir_fd=dir_fd)


def race_rmdir(path, *, dir_fd=None):
    inject(dir_fd, text(path))
    return real_rmdir(path, dir_fd=dir_fd)


class NativeCall:
    def __init__(self, call):
        self.call = call

    @property
    def argtypes(self):
        return self.call.argtypes

    @argtypes.setter
    def argtypes(self, value):
        self.call.argtypes = value

    @property
    def restype(self):
        return self.call.restype

    @restype.setter
    def restype(self, value):
        self.call.restype = value

    def __call__(self, *args):
        source_fd = int(args[0])
        source_name = text(args[1])
        flag = int(args[4])
        if flag in (1, 4):
            inject(source_fd, source_name)
        return self.call(*args)


class LibraryProxy:
    def __init__(self, library):
        self.library = library
        for name in ("renameat2", "renameatx_np"):
            if hasattr(library, name):
                setattr(self, name, NativeCall(getattr(library, name)))

    def __getattr__(self, name):
        return getattr(self.library, name)


def cdll(*args, **kwargs):
    return LibraryProxy(real_cdll(*args, **kwargs))


os.unlink = race_unlink
os.rmdir = race_rmdir
ctypes.CDLL = cdll
PY

  for kind in entry marker directory claim; do
    fixture="$test_root/native-probe-cleanup-atomic-$kind"
    state="$test_root/native-probe-cleanup-atomic-$kind.json"
    output="$test_root/native-probe-cleanup-atomic-$kind.log"
    make_fixture "$fixture"
    holding_root="$(stable_holding_root "$fixture")"
    mkdir -m 0700 "$holding_root"
    set +e
    (
      cd "$fixture"
      exec env PYTHONPATH="$modules" \
        XZWL_PROBE_CLEANUP_RACE_KIND="$kind" \
        XZWL_PROBE_CLEANUP_RACE_STATE="$state" \
        bash scripts/create-service.sh --name "cleanup-race-$kind" --port 8421 \
          --schema "cleanup_race_${kind}_db"
    ) >"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -ne 0 && -s "$state" ]] || {
      printf 'native-probe cleanup RED: %s race was not observed, raw=%s: %s\n' \
        "$kind" "$raw_exit" "$(cat "$output")" >&2
      return 1
    }
    python3 - "$holding_root" "$state" <<'PY'
from pathlib import Path
import json
import re
import sys

root = Path(sys.argv[1])
state = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
kind = state["kind"]
name = state["name"]
if kind in {"entry", "marker"}:
    probes = [
        path for path in root.iterdir()
        if re.fullmatch(r"\.create-service\.native-probe-[0-9a-f]{32}", path.name)
        and path.is_dir() and not path.is_symlink()
    ]
    if len(probes) != 1:
        raise SystemExit(f"expected one visible probe, found {len(probes)}")
    parent = probes[0]
else:
    parent = root
replacement = parent / name
if not replacement.exists() and not replacement.is_symlink():
    raise SystemExit(
        f"native-probe cleanup RED: {kind} third-party replacement was deleted"
    )
info = replacement.lstat()
if (info.st_dev, info.st_ino, info.st_mode) != (
    state["device"], state["inode"], state["mode"],
):
    raise SystemExit(f"{kind} replacement identity changed")
if replacement.is_dir() and not replacement.is_symlink():
    if (replacement / "third-party.txt").read_bytes() != b"third-party-native-probe-race\n":
        raise SystemExit(f"{kind} replacement directory bytes changed")
elif replacement.read_bytes() != b"third-party-native-probe-race\n":
    raise SystemExit(f"{kind} replacement bytes changed")
aside = parent / state["aside"]
if not aside.exists() and not aside.is_symlink():
    raise SystemExit(f"{kind} owned entry was not preserved aside")
PY

    set +e
    (cd "$fixture" && bash scripts/create-service.sh --name "cleanup-race-$kind" \
      --port 8421 --schema "cleanup_race_${kind}_db") >>"$output" 2>&1
    retry_raw=$?
    set -e
    [[ "$retry_raw" -ne 0 ]] || {
      printf 'native-probe cleanup RED: %s unresolved conflict did not fail closed\n' \
        "$kind" >&2
      return 1
    }
    python3 - "$holding_root" "$state" <<'PY'
from pathlib import Path
import json
import re
import sys

root = Path(sys.argv[1])
state = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
kind = state["kind"]
if kind in {"entry", "marker"}:
    parent = next(
        path for path in root.iterdir()
        if re.fullmatch(r"\.create-service\.native-probe-[0-9a-f]{32}", path.name)
        and path.is_dir() and not path.is_symlink()
    )
else:
    parent = root
replacement = parent / state["name"]
info = replacement.lstat()
if (info.st_dev, info.st_ino, info.st_mode) != (
    state["device"], state["inode"], state["mode"],
):
    raise SystemExit(f"retry changed {kind} replacement identity")
if replacement.is_dir() and not replacement.is_symlink():
    (replacement / "third-party.txt").unlink()
    replacement.rmdir()
else:
    replacement.unlink()
(parent / state["aside"]).rename(replacement)
PY
    (cd "$fixture" && bash scripts/create-service.sh --name "cleanup-race-$kind" \
      --port 8421 --schema "cleanup_race_${kind}_db") >>"$output" 2>&1
    assert_generated_service "$fixture" "cleanup-race-$kind" 8421 \
      "cleanup_race_${kind}_db"
    assert_no_transaction_artifacts "$fixture"
  done
}

test_pom_comment_preservation() {
  local fixture="$test_root/pom-comment-preservation"
  make_fixture "$fixture"
  python3 - "$fixture/pom.xml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
path.write_text(path.read_text(encoding="utf-8").replace(
    "<modules>", "<!-- generator-must-preserve-this-comment -->\n  <modules>"
), encoding="utf-8")
PY
  (cd "$fixture" && bash scripts/create-service.sh \
    --name comment-safe --port 8271 --schema comment_safe_db)
  grep -F '<!-- generator-must-preserve-this-comment -->' "$fixture/pom.xml" >/dev/null || {
    printf 'strict-metadata RED: root POM comment was discarded\n' >&2
    exit 1
  }
  assert_generated_service "$fixture" comment-safe 8271 comment_safe_db
}

test_strict_catalog_validation() {
  local fixture
  local case_name
  local failures=()
  for case_name in bool-version owner-type schema-type port-type array-non-string array-duplicate duplicate-schema; do
    fixture="$test_root/catalog-$case_name"
    make_fixture "$fixture"
    python3 - "$fixture/architecture/service-catalog.yaml" "$case_name" <<'PY'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
case = sys.argv[2]
data = json.loads(path.read_text(encoding="utf-8"))
if case == "bool-version":
    data["version"] = True
elif case == "owner-type":
    data["services"][0]["owner"] = ["platform-team"]
elif case == "schema-type":
    data["services"][0]["schema"] = 17
elif case == "port-type":
    data["services"][0]["port"] = "8200"
elif case == "array-non-string":
    data["services"][0]["publicApi"] = [17]
elif case == "array-duplicate":
    data["services"][0]["publicApi"] = ["/api/platform/smoke", "/api/platform/smoke"]
elif case == "duplicate-schema":
    data["services"][0]["schema"] = "duplicate_db"
    data["services"][1]["schema"] = "duplicate_db"
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY
    if ! (expect_failure_preserves "$fixture" 'service catalog' \
      --name "strict-$case_name" --port 8272 --schema "strict_${case_name//-/_}_db"); then
      failures+=("$case_name")
    fi
  done
  [[ "${#failures[@]}" -eq 0 ]] || {
    printf 'strict-catalog RED cases: %s\n' "${failures[*]}" >&2
    return 1
  }
}

test_strict_metadata_and_pom_comment_preservation() {
  test_pom_comment_preservation
  test_strict_catalog_validation
}

test_native_operations_are_dirfd_relative() {
  local modules="$test_root/dirfd-native-modules"
  local failures=()
  mkdir -p "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import ctypes
import json
import os
from pathlib import Path

_real_cdll = ctypes.CDLL


def _text(value):
    if isinstance(value, bytes):
        return os.fsdecode(value)
    return os.fspath(value)


class _NativeCall:
    def __init__(self, name, call):
        self.name = name
        self.call = call

    @property
    def argtypes(self):
        return self.call.argtypes

    @argtypes.setter
    def argtypes(self, value):
        self.call.argtypes = value

    @property
    def restype(self):
        return self.call.restype

    @restype.setter
    def restype(self, value):
        self.call.restype = value

    def __call__(self, *args):
        if self.name == "renamex_np":
            source_fd = destination_fd = None
            source = _text(args[0])
            destination = _text(args[1])
        else:
            source_fd = int(args[0])
            source = _text(args[1])
            destination_fd = int(args[2])
            destination = _text(args[3])

        replace_root = os.environ.get("XZWL_DIRFD_REPLACE_PROBE_ROOT")
        replacement_state = os.environ.get("XZWL_DIRFD_REPLACEMENT_STATE")
        if replace_root and Path(source).name == "exchange-first" and replacement_state:
            state_path = Path(replacement_state)
            if not state_path.exists():
                root = Path(replace_root)
                probes = [
                    candidate for candidate in root.glob(".create-service.native-probe-*")
                    if candidate.is_dir() and not candidate.is_symlink()
                ]
                if len(probes) != 1:
                    raise RuntimeError(f"expected one probe to replace, found {len(probes)}")
                probe = probes[0]
                owned_aside = root / "probe-owned-aside"
                os.rename(probe, owned_aside)
                os.mkdir(probe, 0o751)
                third_party = probe / "third-party.txt"
                third_party.write_bytes(b"third-party-dirfd-probe\n")
                info = probe.lstat()
                state_path.write_text(json.dumps({
                    "path": str(probe),
                    "device": info.st_dev,
                    "inode": info.st_ino,
                    "mode": info.st_mode,
                    "ownedAside": str(owned_aside),
                }), encoding="utf-8")

        result = self.call(*args)
        saved_errno = ctypes.get_errno()
        log = os.environ.get("XZWL_NATIVE_DIRFD_LOG")
        if log:
            with open(log, "a", encoding="utf-8") as stream:
                stream.write(json.dumps({
                    "operation": self.name,
                    "sourceFd": source_fd,
                    "source": source,
                    "destinationFd": destination_fd,
                    "destination": destination,
                    "result": int(result),
                    "errno": int(saved_errno),
                }) + "\n")
        ctypes.set_errno(saved_errno)
        return result


class _LibraryProxy:
    def __init__(self, library):
        self._library = library
        for name in ("renameat2", "renameatx_np", "renamex_np"):
            if hasattr(library, name):
                setattr(self, name, _NativeCall(name, getattr(library, name)))

    def __getattr__(self, name):
        return getattr(self._library, name)


def _cdll(*args, **kwargs):
    return _LibraryProxy(_real_cdll(*args, **kwargs))


ctypes.CDLL = _cdll
PY

  local fixture="$test_root/dirfd-probe-path-replacement"
  local output="$test_root/dirfd-probe-path-replacement.log"
  local native_log="$test_root/dirfd-probe-path-replacement.native.jsonl"
  local replacement_state="$test_root/dirfd-probe-path-replacement.state.json"
  local raw_exit
  make_fixture "$fixture"
  set +e
  (cd "$fixture" && \
    PYTHONPATH="$modules" \
    XZWL_NATIVE_DIRFD_LOG="$native_log" \
    XZWL_DIRFD_REPLACE_PROBE_ROOT="$(stable_holding_root "$fixture")" \
    XZWL_DIRFD_REPLACEMENT_STATE="$replacement_state" \
    bash scripts/create-service.sh --name dirfd-probe --port 8290 \
      --schema dirfd_probe_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || failures+=("probe pathname replacement was not fail-closed")
  if [[ ! -s "$replacement_state" || ! -s "$native_log" ]]; then
    failures+=("probe pathname replacement did not reach the native operation")
  elif ! python3 - "$replacement_state" "$native_log" <<'PY'
from pathlib import Path
import json
import os
import stat
import sys

state = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
path = Path(state["path"])
info = path.lstat()
if (info.st_dev, info.st_ino, info.st_mode) != (state["device"], state["inode"], state["mode"]):
    raise SystemExit("replacement probe directory inode or mode changed")
if (path / "third-party.txt").read_bytes() != b"third-party-dirfd-probe\n":
    raise SystemExit("replacement probe directory bytes changed")
records = [json.loads(line) for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()]
exchange = next(record for record in records if Path(record["source"]).name == "exchange-first")
if exchange["operation"] == "renamex_np" or exchange["sourceFd"] in (None, -100):
    raise SystemExit("probe native exchange was pathname-relative instead of dirfd-relative")
if exchange["result"] != 0:
    raise SystemExit("probe native exchange followed the replaced pathname")
PY
  then
    failures+=("probe native operation followed or trusted a replaced pathname")
  fi
  test ! -e "$fixture/services/dirfd-probe-service"
  test "$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f | wc -l | tr -d ' ')" -eq 0

  fixture="$test_root/dirfd-main-native"
  local success_log="$test_root/dirfd-main-success.native.jsonl"
  local recovery_log="$test_root/dirfd-main-recovery.native.jsonl"
  output="$test_root/dirfd-main-native.log"
  make_fixture "$fixture"
  set +e
  (cd "$fixture" && \
    PYTHONPATH="$modules" XZWL_NATIVE_DIRFD_LOG="$success_log" \
    bash scripts/create-service.sh --name dirfd-main --port 8291 \
      --schema dirfd_main_db) >"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || failures+=("dirfd logging success fixture failed")

  set +e
  (cd "$fixture" && \
    PYTHONPATH="$modules" XZWL_NATIVE_DIRFD_LOG="$test_root/dirfd-main-crash.native.jsonl" \
    XZWL_CREATE_SERVICE_TEST_FAULTS='crash-after-catalog-publish' \
    bash scripts/create-service.sh --name dirfd-recovery --port 8292 \
      --schema dirfd_recovery_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 97 ]] || failures+=("dirfd recovery fixture did not stop at raw 97")
  set +e
  (cd "$fixture" && \
    PYTHONPATH="$modules" XZWL_NATIVE_DIRFD_LOG="$recovery_log" \
    bash scripts/create-service.sh --name dirfd-recovery --port 8292 \
      --schema dirfd_recovery_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || failures+=("dirfd recovery fixture did not recover")

  if ! python3 - "$success_log" "$recovery_log" <<'PY'
from pathlib import Path
import json
import sys


def load(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]


def validate(records, label):
    if not records:
        raise SystemExit(f"{label}: no native calls recorded")
    for record in records:
        if record["operation"] == "renamex_np":
            raise SystemExit(f"{label}: path-only Darwin renamex_np was used")
        if record["sourceFd"] in (None, -100) or record["destinationFd"] in (None, -100):
            raise SystemExit(f"{label}: AT_FDCWD/path-only native call: {record}")
        for key in ("source", "destination"):
            value = record[key]
            if value in ("", ".", "..") or "/" in value or Path(value).name != value:
                raise SystemExit(f"{label}: native operand is not a single basename: {record}")


success = load(sys.argv[1])
recovery = load(sys.argv[2])
validate(success, "success")
validate(recovery, "recovery")
pairs = {(item["source"], item["destination"]) for item in success}
required = {
    ("exchange-first", "exchange-second"),
    ("pom.xml", "pom.next"),
    ("service-catalog.yaml", "catalog.next"),
    ("dirfd-main-service", "dirfd-main-service"),
}
missing = required - pairs
if missing:
    raise SystemExit(f"success: missing probe/POM/catalog/target dirfd calls: {sorted(missing)}")
recovery_pairs = {(item["source"], item["destination"]) for item in recovery}
if not ({("pom.xml", "pom.next"), ("service-catalog.yaml", "catalog.next")} <= recovery_pairs):
    raise SystemExit(f"recovery: missing dirfd metadata recovery calls: {sorted(recovery_pairs)}")
PY
  then
    failures+=("POM/catalog/target/recovery native calls were not basename+dirfd relative")
  fi

  [[ "${#failures[@]}" -eq 0 ]] || {
    printf 'dirfd-native RED cases: %s\n' "${failures[*]}" >&2
    return 1
  }
}

test_recovery_required_is_manual_only() {
  local fixture="$test_root/stage-recovery-required-manual"
  local output="$test_root/stage-recovery-required-manual.log"
  local journal
  local before
  local raw_exit
  crash_transaction "$fixture" manual-stage 8293 manual_stage_db \
    after-journal-committed "$output"
  journal="$(pending_journal_path "$fixture")"
  python3 - "$journal" <<'PY'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
data["stage"] = "RECOVERY_REQUIRED"
data["recoveryErrors"] = ["manual recovery evidence must be preserved"]
path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
  before="$(snapshot_recovery_state "$fixture")"
  set +e
  (cd "$fixture" && bash scripts/create-service.sh \
    --name manual-stage --port 8293 --schema manual_stage_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'stage-machine RED: RECOVERY_REQUIRED was automatically completed\n' >&2
    return 1
  }
  grep -F 'manual recovery required' "$output" >/dev/null || {
    printf 'stage-machine RED: RECOVERY_REQUIRED lacked explicit manual diagnostic: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  [[ "$before" == "$(snapshot_recovery_state "$fixture")" ]] || {
    printf 'stage-machine RED: RECOVERY_REQUIRED changed repository or journal bytes/inodes\n' >&2
    return 1
  }
}

test_committed_recovery_conflicts_are_preserved() {
  local case_name
  local fixture
  local output
  local journal
  local target
  local raw_exit
  for case_name in missing replaced metadata-external; do
    fixture="$test_root/stage-committed-$case_name"
    output="$test_root/stage-committed-$case_name.log"
    crash_transaction "$fixture" "committed-$case_name" 8294 \
      "committed_${case_name//-/_}_db" after-journal-committed "$output"
    journal="$(pending_journal_path "$fixture")"
    assert_journal_stage "$journal" COMMITTED
    target="$fixture/services/committed-$case_name-service"
    cp "$fixture/pom.xml" "$test_root/committed-$case_name.pom.intended"
    cp "$fixture/architecture/service-catalog.yaml" \
      "$test_root/committed-$case_name.catalog.intended"
    case "$case_name" in
      missing)
        mv "$target" "$fixture/committed-missing-target-preserved"
        ;;
      replaced)
        mv "$target" "$fixture/committed-replaced-target-preserved"
        mkdir "$target"
        printf 'third-party-committed-target\n' > "$target/third-party.txt"
        ;;
      metadata-external)
        python3 - "$fixture/pom.xml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
path.write_text(path.read_text(encoding="utf-8").replace(
    "</project>", "  <!-- external committed metadata -->\n</project>"
), encoding="utf-8")
PY
        cp "$fixture/pom.xml" "$test_root/committed-metadata-external.pom.intended"
        ;;
    esac
    set +e
    (cd "$fixture" && bash scripts/create-service.sh \
      --name "committed-$case_name" --port 8294 \
      --schema "committed_${case_name//-/_}_db") >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -ne 0 ]] || {
      printf 'stage-machine RED: COMMITTED %s was automatically rolled back/regenerated\n' \
        "$case_name" >&2
      return 1
    }
    journal="$(pending_journal_path "$fixture")"
    assert_journal_stage "$journal" RECOVERY_REQUIRED
    cmp -s "$fixture/pom.xml" "$test_root/committed-$case_name.pom.intended" || {
      printf 'stage-machine RED: COMMITTED %s rolled back/overwrote root POM\n' \
        "$case_name" >&2
      return 1
    }
    cmp -s "$fixture/architecture/service-catalog.yaml" \
      "$test_root/committed-$case_name.catalog.intended" || {
      printf 'stage-machine RED: COMMITTED %s rolled back/overwrote catalog\n' \
        "$case_name" >&2
      return 1
    }
    case "$case_name" in
      missing) test -d "$fixture/committed-missing-target-preserved" ;;
      replaced) grep -Fx 'third-party-committed-target' "$target/third-party.txt" >/dev/null ;;
      metadata-external) test -d "$target" ;;
    esac
  done
}

test_stage_driven_recovery_matrix() {
  local stage
  local point
  local index=0
  local fixture
  local output
  local journal
  local raw_exit

  for stage in PREPARING PREPARED POM_EXCHANGED CATALOG_EXCHANGED COMMITTED; do
    index=$((index + 1))
    fixture="$test_root/stage-normal-$stage"
    output="$test_root/stage-normal-$stage.log"
    case "$stage" in
      PREPARING|PREPARED|POM_EXCHANGED) point=after-pom-publish ;;
      CATALOG_EXCHANGED) point=after-catalog-publish ;;
      COMMITTED) point=after-journal-committed ;;
    esac
    crash_transaction "$fixture" "stage$index" "$((63000 + index))" \
      "stage${index}_db" "$point" "$output"
    journal="$(pending_journal_path "$fixture")"
    if [[ "$stage" == PREPARING || "$stage" == PREPARED ]]; then
      python3 - "$fixture" "$journal" "$stage" <<'PY'
from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
journal = Path(sys.argv[2])
stage = sys.argv[3]
transaction = journal.parent
live = root / "pom.xml"
slot = transaction / "pom.next"
intended = live.read_bytes()
original = slot.read_bytes()
live.write_bytes(original)
slot.write_bytes(intended)
data = json.loads(journal.read_text(encoding="utf-8"))
data["stage"] = stage
if stage == "PREPARING":
    data["pomIntendedSha256"] = None
    data["catalogIntendedSha256"] = None
journal.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
    fi
    assert_journal_stage "$journal" "$stage"
    set +e
    (cd "$fixture" && bash scripts/create-service.sh \
      --name "stage$index" --port "$((63000 + index))" --schema "stage${index}_db") \
      >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -eq 0 ]] || {
      printf 'stage-machine RED: normal %s state did not recover: raw=%s output=%s\n' \
        "$stage" "$raw_exit" "$(cat "$output")" >&2
      return 1
    }
    assert_generated_service "$fixture" "stage$index" "$((63000 + index))" "stage${index}_db"
    assert_no_transaction_artifacts "$fixture"
  done

  fixture="$test_root/stage-publishing-absent"
  local hook_dir="$test_root/stage-publishing-absent-hook"
  output="$test_root/stage-publishing-absent.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='metadata-committed-before-final-publish' \
      bash scripts/create-service.sh --name publishing-absent --port 8300 \
        --schema publishing_absent_db
  ) >"$output" 2>&1 &
  local child=$!
  set -e
  wait_for_test_path "$hook_dir/metadata-committed-before-final-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'stage-machine RED: missing PUBLISHING-before-target seam\n' >&2
    return 1
  }
  kill -KILL "$child"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]]
  journal="$(pending_journal_path "$fixture")"
  assert_journal_stage "$journal" PUBLISHING
  test ! -e "$fixture/services/publishing-absent-service"
  set +e
  (cd "$fixture" && bash scripts/create-service.sh --name publishing-absent \
    --port 8300 --schema publishing_absent_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -eq 0 ]] || {
    printf 'stage-machine RED: PUBLISHING target-absent state did not safely rollback/retry\n' >&2
    return 1
  }
  assert_generated_service "$fixture" publishing-absent 8300 publishing_absent_db
  assert_no_transaction_artifacts "$fixture"

  fixture="$test_root/stage-publishing-external"
  hook_dir="$test_root/stage-publishing-external-hook"
  output="$test_root/stage-publishing-external.log"
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  set +e
  (
    cd "$fixture"
    exec env XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='metadata-committed-before-final-publish' \
      bash scripts/create-service.sh --name publishing-external --port 8301 \
        --schema publishing_external_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/metadata-committed-before-final-publish.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'stage-machine RED: missing external PUBLISHING seam\n' >&2
    return 1
  }
  kill -KILL "$child"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  mkdir "$fixture/services/publishing-external-service"
  printf 'third-party-publishing-target\n' \
    > "$fixture/services/publishing-external-service/third-party.txt"
  cp "$fixture/pom.xml" "$test_root/publishing-external.pom.intended"
  cp "$fixture/architecture/service-catalog.yaml" \
    "$test_root/publishing-external.catalog.intended"
  set +e
  (cd "$fixture" && bash scripts/create-service.sh --name publishing-external \
    --port 8301 --schema publishing_external_db) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'stage-machine RED: PUBLISHING external target was not fail-closed\n' >&2
    return 1
  }
  journal="$(find "$fixture" -maxdepth 2 -name RECOVERY.json -type f -print -quit)"
  [[ -n "$journal" ]] || {
    printf 'stage-machine RED: PUBLISHING external target rolled metadata back and deleted the journal\n' >&2
    return 1
  }
  assert_journal_stage "$journal" RECOVERY_REQUIRED
  cmp -s "$fixture/pom.xml" "$test_root/publishing-external.pom.intended"
  cmp -s "$fixture/architecture/service-catalog.yaml" \
    "$test_root/publishing-external.catalog.intended"
  grep -Fx 'third-party-publishing-target' \
    "$fixture/services/publishing-external-service/third-party.txt" >/dev/null

  local conflict_stage
  local crash_point
  local conflict_slug
  local conflict_name
  for conflict_stage in PREPARED POM_EXCHANGED; do
    conflict_slug="$(printf '%s' "$conflict_stage" | tr '[:upper:]' '[:lower:]')"
    conflict_name="$(printf '%s' "$conflict_slug" | tr '_' '-')"
    fixture="$test_root/stage-future-$conflict_stage"
    output="$test_root/stage-future-$conflict_stage.log"
    [[ "$conflict_stage" == PREPARED ]] \
      && crash_point=after-pom-publish \
      || crash_point=after-catalog-publish
    crash_transaction "$fixture" "future-$conflict_name" 8302 \
      "future_${conflict_slug}_db" "$crash_point" "$output"
    journal="$(pending_journal_path "$fixture")"
    python3 - "$journal" "$conflict_stage" <<'PY'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
data["stage"] = sys.argv[2]
path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
    cp "$fixture/pom.xml" "$test_root/future-$conflict_stage.pom"
    cp "$fixture/architecture/service-catalog.yaml" "$test_root/future-$conflict_stage.catalog"
    set +e
    (cd "$fixture" && bash scripts/create-service.sh \
      --name "future-$conflict_name" --port 8302 \
      --schema "future_${conflict_slug}_db") >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -ne 0 ]] || {
      printf 'stage-machine RED: %s future-node state was automatically rolled back\n' \
        "$conflict_stage" >&2
      return 1
    }
    journal="$(pending_journal_path "$fixture")"
    assert_journal_stage "$journal" RECOVERY_REQUIRED
    cmp -s "$fixture/pom.xml" "$test_root/future-$conflict_stage.pom"
    cmp -s "$fixture/architecture/service-catalog.yaml" "$test_root/future-$conflict_stage.catalog"
  done
}

test_metadata_exchange_compensates_nonregular_entries() {
  local label
  local kind
  local index=0
  local fixture
  local hook_dir
  local hook_point
  local compensation_point
  local output
  local live
  local original
  local intended
  local transaction
  local slot
  local third_party_target
  local service_name
  local before_inode
  local after_inode
  local child
  local raw_exit

  for label in pom catalog; do
    for kind in symlink directory; do
      index=$((index + 1))
      fixture="$test_root/metadata-nonregular-$label-$kind"
      hook_dir="$test_root/metadata-nonregular-$label-$kind-hook"
      output="$test_root/metadata-nonregular-$label-$kind.log"
      original="$test_root/metadata-nonregular-$label-$kind.original"
      intended="$test_root/metadata-nonregular-$label-$kind.intended"
      third_party_target="$test_root/metadata-nonregular-$label-$kind.third-party"
      service_name="entry-race-case$index"
      make_fixture "$fixture"
      mkdir -p "$hook_dir"
      cp "$fixture/pom.xml" "$test_root/metadata-nonregular-$label-$kind.pom.before"
      cp "$fixture/architecture/service-catalog.yaml" \
        "$test_root/metadata-nonregular-$label-$kind.catalog.before"
      if [[ "$label" == pom ]]; then
        live="$fixture/pom.xml"
        hook_point=after-final-metadata-check-before-pom-publish
        compensation_point=after-pom-safe-exchange-compensation
      else
        live="$fixture/architecture/service-catalog.yaml"
        hook_point=after-pom-publish
        compensation_point=after-catalog-safe-exchange-compensation
      fi

      set +e
      (
        cd "$fixture"
        exec env \
          XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
          XZWL_CREATE_SERVICE_TEST_HOOK_POINTS="$hook_point,$compensation_point" \
          bash scripts/create-service.sh --name "$service_name" \
            --port "$((8310 + index))" --schema "entry_race_${index}_db"
      ) >"$output" 2>&1 &
      child=$!
      set -e
      wait_for_test_path "$hook_dir/$hook_point.ready" "$child" || {
        wait "$child" 2>/dev/null || true
        printf 'metadata-entry RED: missing %s seam: %s\n' \
          "$hook_point" "$(cat "$output")" >&2
        return 1
      }

      transaction="$(find "$fixture" -maxdepth 1 -type d -name '.create-service.*' -print -quit)"
      [[ -n "$transaction" ]] || {
        printf 'metadata-entry RED: %s transaction directory is missing before exchange\n' \
          "$label" >&2
        return 1
      }
      slot="$transaction/$label.next"
      cp "$slot" "$intended"

      mv "$live" "$original"
      if [[ "$kind" == symlink ]]; then
        printf 'third-party-%s-%s\n' "$label" "$kind" > "$third_party_target"
        ln -s "$third_party_target" "$live"
      else
        mkdir "$live"
        printf 'third-party-%s-%s\n' "$label" "$kind" > "$live/third-party.txt"
      fi
      before_inode="$(inode_key "$live")"
      : > "$hook_dir/$hook_point.continue"
      wait_for_test_path "$hook_dir/$compensation_point.ready" "$child" || {
        wait "$child" 2>/dev/null || true
        printf 'metadata-entry RED: missing %s seam: %s\n' \
          "$compensation_point" "$(cat "$output")" >&2
        return 1
      }
      [[ "$(inode_key "$live")" == "$before_inode" ]] || {
        printf 'metadata-entry RED: %s %s identity was not restored before cleanup\n' \
          "$label" "$kind" >&2
        return 1
      }
      cmp -s "$slot" "$intended" || {
        printf 'metadata-entry RED: %s intended file was not returned to its transaction slot\n' \
          "$label" >&2
        return 1
      }
      : > "$hook_dir/$compensation_point.continue"
      set +e
      wait "$child"; raw_exit=$?
      set -e
      [[ "$raw_exit" -ne 0 ]] || {
        printf 'metadata-entry RED: %s %s replacement unexpectedly committed\n' \
          "$label" "$kind" >&2
        return 1
      }
      if [[ ! -e "$live" && ! -L "$live" ]]; then
        printf 'metadata-entry RED: %s %s replacement disappeared\n' "$label" "$kind" >&2
        return 1
      fi
      after_inode="$(inode_key "$live")"
      [[ "$after_inode" == "$before_inode" ]] || {
        printf 'metadata-entry RED: %s %s identity changed: before=%s after=%s output=%s\n' \
          "$label" "$kind" "$before_inode" "$after_inode" "$(cat "$output")" >&2
        return 1
      }
      if [[ "$kind" == symlink ]]; then
        [[ -L "$live" && "$(readlink "$live")" == "$third_party_target" ]] || {
          printf 'metadata-entry RED: %s symlink type/target was not restored\n' "$label" >&2
          return 1
        }
        grep -Fx "third-party-$label-$kind" "$third_party_target" >/dev/null
      else
        [[ -d "$live" ]] || {
          printf 'metadata-entry RED: %s directory type was not restored\n' "$label" >&2
          return 1
        }
        grep -Fx "third-party-$label-$kind" "$live/third-party.txt" >/dev/null
      fi
      if [[ "$label" == pom ]]; then
        cmp -s "$original" "$test_root/metadata-nonregular-$label-$kind.pom.before"
        cmp -s "$fixture/architecture/service-catalog.yaml" \
          "$test_root/metadata-nonregular-$label-$kind.catalog.before"
      else
        cmp -s "$fixture/pom.xml" "$test_root/metadata-nonregular-$label-$kind.pom.before"
        cmp -s "$original" "$test_root/metadata-nonregular-$label-$kind.catalog.before"
      fi
      test ! -e "$fixture/services/$service_name-service"
      assert_no_transaction_artifacts "$fixture"
    done
  done
}

test_metadata_exchange_compensation_detects_slot_replacement() {
  local fixture="$test_root/metadata-exchange-slot-replacement"
  local hook_dir="$test_root/metadata-exchange-slot-replacement-hook"
  local output="$test_root/metadata-exchange-slot-replacement.log"
  local pom_before="$test_root/metadata-exchange-slot-replacement.pom.before"
  local catalog_before="$test_root/metadata-exchange-slot-replacement.catalog.before"
  local intended="$test_root/metadata-exchange-slot-replacement.pom.intended"
  local displaced="$test_root/metadata-exchange-slot-replacement.pom.displaced"
  local transaction
  local slot
  local third_party_inode
  local child
  local raw_exit
  make_fixture "$fixture"
  mkdir -p "$hook_dir"
  cp "$fixture/pom.xml" "$pom_before"
  cp "$fixture/architecture/service-catalog.yaml" "$catalog_before"

  set +e
  (
    cd "$fixture"
    exec env \
      XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hook_dir" \
      XZWL_CREATE_SERVICE_TEST_HOOK_POINTS='after-pom-native-exchange-before-inspection' \
      bash scripts/create-service.sh --name entry-slot-race --port 8319 \
        --schema entry_slot_race_db
  ) >"$output" 2>&1 &
  child=$!
  set -e
  wait_for_test_path "$hook_dir/after-pom-native-exchange-before-inspection.ready" "$child" || {
    wait "$child" 2>/dev/null || true
    printf 'metadata-entry RED: missing post-exchange/pre-inspection seam: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  transaction="$(find "$fixture" -maxdepth 1 -type d -name '.create-service.*' -print -quit)"
  [[ -n "$transaction" ]] || {
    printf 'metadata-entry RED: post-exchange transaction directory is missing\n' >&2
    return 1
  }
  slot="$transaction/pom.next"
  cp "$fixture/pom.xml" "$intended"
  mv "$slot" "$displaced"
  printf 'third-party-slot-replacement\n' > "$slot"
  third_party_inode="$(inode_key "$slot")"
  : > "$hook_dir/after-pom-native-exchange-before-inspection.continue"
  set +e
  wait "$child"; raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'metadata-entry RED: post-exchange slot replacement unexpectedly committed\n' >&2
    return 1
  }
  [[ -f "$slot" && "$(inode_key "$slot")" == "$third_party_inode" ]] || {
    printf 'metadata-entry RED: post-exchange third-party slot was moved or overwritten: %s\n' \
      "$(cat "$output")" >&2
    return 1
  }
  grep -Fx 'third-party-slot-replacement' "$slot" >/dev/null
  cmp -s "$fixture/pom.xml" "$intended" || {
    printf 'metadata-entry RED: unsafe compensation replaced live POM after slot race\n' >&2
    return 1
  }
  cmp -s "$displaced" "$pom_before"
  cmp -s "$fixture/architecture/service-catalog.yaml" "$catalog_before"
  local journal
  journal="$(pending_journal_path "$fixture")"
  assert_journal_stage "$journal" RECOVERY_REQUIRED
  test ! -e "$fixture/services/entry-slot-race-service"
}

test_recovery_cleanup_owns_each_descriptor_once() {
  local modules="$test_root/recovery-cleanup-shim"
  local mode
  local index=0
  local fixture
  local output
  local marker
  local committed_before
  local failure_raw_exit
  local raw_exit
  mkdir -p "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import inspect
import os
from pathlib import Path
import shutil

mode = os.environ.get("XZWL_RECOVERY_CLEANUP_SHIM_MODE", "")
marker = Path(os.environ.get("XZWL_RECOVERY_CLEANUP_SHIM_MARKER", "/nonexistent"))
sentinel_fd = None
released_cleanup_fd = None
cleanup_fsync_count = 0
cleanup_unlink_count = 0
fault_injected = False
real_close = os.close
real_fsync = os.fsync
real_rmdir = os.rmdir
real_rmtree = shutil.rmtree
real_unlink = os.unlink

def in_cleanup():
    return any(frame.function == "remove_recovered_directory" for frame in inspect.stack())

def caller_transaction_fd():
    for frame in inspect.stack():
        if frame.function == "recover_pending_transactions":
            return frame.frame.f_locals.get("transaction_fd")
    return None

def cleanup_quarantine():
    for frame in inspect.stack():
        if frame.function == "remove_recovered_directory":
            name = frame.frame.f_locals.get("quarantine_name")
            transaction = frame.frame.f_locals.get("self")
            if name and transaction is not None:
                return transaction.repo_root / name
    return None

def cleanup_journal_removed():
    descriptor = caller_transaction_fd()
    if descriptor is None:
        return False
    try:
        os.stat("RECOVERY.json", dir_fd=descriptor, follow_symlinks=False)
        return False
    except FileNotFoundError:
        return True

def arm_sentinel():
    global sentinel_fd
    if sentinel_fd is None:
        sentinel_path = Path(str(marker) + ".sentinel")
        sentinel_fd = os.open(sentinel_path, os.O_RDWR | os.O_CREAT | os.O_TRUNC, 0o600)
        marker.write_text(
            f"{sentinel_fd}:{released_cleanup_fd}:{mode}",
            encoding="utf-8",
        )

def guarded_close(descriptor):
    global released_cleanup_fd
    if (
        mode
        and sentinel_fd is None
        and in_cleanup()
        and descriptor == caller_transaction_fd()
    ):
        released_cleanup_fd = descriptor
    if descriptor == sentinel_fd:
        Path(str(marker) + ".closed").write_text(str(descriptor), encoding="utf-8")
    return real_close(descriptor)

def guarded_fsync(descriptor):
    global cleanup_fsync_count, fault_injected
    if in_cleanup():
        cleanup_fsync_count += 1
        quarantine = cleanup_quarantine()
        if mode == "pre-fsync" and cleanup_fsync_count == 1 and not fault_injected:
            fault_injected = True
            arm_sentinel()
            raise OSError(
                5, f"injected recovered transaction {mode} cleanup failure"
            )
        if (
            mode == "post-fsync"
            and not fault_injected
            and quarantine is not None
            and not os.path.lexists(quarantine)
            and cleanup_journal_removed()
        ):
            fault_injected = True
            arm_sentinel()
            raise OSError(
                5, f"injected recovered transaction {mode} cleanup failure"
            )
    return real_fsync(descriptor)

def guarded_rmtree(path, *args, **kwargs):
    global fault_injected
    root = Path(path)
    if root.name.startswith(".create-service.cleanup-"):
        if mode == "delete-before":
            fault_injected = True
            arm_sentinel()
            raise OSError(5, "injected recovered transaction delete-before cleanup failure")
        if mode == "child-delete":
            real_unlink(root / "pom.backup")
            arm_sentinel()
            raise OSError(5, "injected recovered transaction child-delete cleanup failure")
        if mode == "partial-rmtree":
            real_unlink(root / "RECOVERY.json")
            real_unlink(root / "pom.backup")
            arm_sentinel()
            raise OSError(5, "injected recovered transaction partial-rmtree cleanup failure")
        if mode == "journal-rmdir":
            for child in root.iterdir():
                if child.is_dir() and not child.is_symlink():
                    real_rmtree(child)
                else:
                    real_unlink(child)
            arm_sentinel()
            raise OSError(5, "injected recovered transaction journal-rmdir cleanup failure")
    return real_rmtree(path, *args, **kwargs)

def guarded_unlink(path, *args, **kwargs):
    global cleanup_unlink_count, fault_injected
    name = os.fsdecode(path)
    if in_cleanup() and Path(name).name.startswith(".cleanup-entry-"):
        if mode == "concurrent-entry" and not fault_injected:
            result = real_unlink(path, *args, **kwargs)
            cleanup_unlink_count += 1
            fault_injected = True
            quarantine = cleanup_quarantine()
            if quarantine is None:
                raise RuntimeError("cleanup quarantine was unavailable to the test shim")
            (quarantine / "third-party-during-cleanup.txt").write_text(
                "third-party-during-cleanup\n", encoding="utf-8"
            )
            return result
        if mode == "delete-before" and not fault_injected:
            fault_injected = True
            arm_sentinel()
            raise OSError(5, "injected recovered transaction delete-before cleanup failure")
        if (
            mode in ("child-delete", "partial-rmtree")
            and cleanup_unlink_count == 0
            and not fault_injected
        ):
            result = real_unlink(path, *args, **kwargs)
            cleanup_unlink_count += 1
            fault_injected = True
            arm_sentinel()
            raise OSError(5, f"injected recovered transaction {mode} cleanup failure")
    return real_unlink(path, *args, **kwargs)

def guarded_rmdir(path, *args, **kwargs):
    global fault_injected
    name = os.fsdecode(path)
    if (
        mode == "journal-rmdir"
        and not fault_injected
        and in_cleanup()
        and Path(name).name.startswith(".create-service.cleanup-")
    ):
        fault_injected = True
        arm_sentinel()
        raise OSError(5, "injected recovered transaction journal-rmdir cleanup failure")
    return real_rmdir(path, *args, **kwargs)

os.close = guarded_close
os.fsync = guarded_fsync
os.rmdir = guarded_rmdir
os.unlink = guarded_unlink
shutil.rmtree = guarded_rmtree
PY

  for mode in pre-fsync delete-before child-delete partial-rmtree journal-rmdir post-fsync; do
    index=$((index + 1))
    local mode_slug="${mode//-/_}"
    fixture="$test_root/recovery-cleanup-$mode"
    output="$test_root/recovery-cleanup-$mode.log"
    marker="$test_root/recovery-cleanup-$mode.marker"
    crash_transaction "$fixture" "cleanup-$mode" "$((8330 + index))" \
      "cleanup_${mode_slug}_db" after-journal-committed "$output"
    printf 'third-party-cleanup-%s\n' "$mode" \
      > "$fixture/services/cleanup-$mode-service/third-party.txt"
    committed_before="$(snapshot_state "$fixture")"

    set +e
    (
      cd "$fixture"
      exec env PYTHONPATH="$modules" \
        XZWL_RECOVERY_CLEANUP_SHIM_MODE="$mode" \
        XZWL_RECOVERY_CLEANUP_SHIM_MARKER="$marker" \
        bash scripts/create-service.sh --name "cleanup-$mode" --port "$((8330 + index))" \
          --schema "cleanup_${mode_slug}_db"
    ) >>"$output" 2>&1
    raw_exit=$?
    failure_raw_exit=$raw_exit
    set -e
    [[ "$raw_exit" -ne 0 ]] || {
      printf 'recovery-cleanup RED: injected %s failure unexpectedly succeeded\n' "$mode" >&2
      return 1
    }
    grep -F "injected recovered transaction $mode cleanup failure" "$output" >/dev/null || {
      printf 'recovery-cleanup RED: %s original cleanup diagnostic was masked: %s\n' \
        "$mode" "$(cat "$output")" >&2
      return 1
    }
    if [[ "$mode" == "journal-rmdir" || "$mode" == "post-fsync" ]]; then
      grep -F 'recovery evidence retained at' "$output" >/dev/null || {
        printf 'recovery-cleanup RED: %s failure lacked retained-evidence path: %s\n' \
          "$mode" "$(cat "$output")" >&2
        return 1
      }
    fi
    if grep -E 'Bad file descriptor|Errno 9|EBADF' "$output" >/dev/null; then
      printf 'recovery-cleanup RED: %s cleanup was masked by EBADF: %s\n' \
        "$mode" "$(cat "$output")" >&2
      return 1
    fi
    [[ ! -e "$marker.closed" ]] || {
      printf 'recovery-cleanup RED: caller finally closed reused sentinel fd for %s\n' \
        "$mode" >&2
      return 1
    }
    [[ -f "$marker" && "$(cat "$marker")" == *":None:$mode" ]] || {
      printf 'recovery-cleanup RED: callee released the caller-owned transaction fd for %s: %s\n' \
        "$mode" "$([[ -f "$marker" ]] && cat "$marker" || printf missing)" >&2
      return 1
    }
    [[ "$committed_before" == "$(snapshot_state "$fixture")" ]] || {
      printf 'recovery-cleanup RED: %s failure changed committed metadata or target bytes\n' \
        "$mode" >&2
      return 1
    }
    if ! pending_journal_path "$fixture" >/dev/null; then
      set +e
      (cd "$fixture" && bash scripts/create-service.sh --name "cleanup-$mode" \
        --port "$((8330 + index))" --schema "cleanup_${mode_slug}_db") >>"$output" 2>&1
      raw_exit=$?
      set -e
      grep -F 'RECOVERY.json must be a regular file' "$output" >/dev/null || true
      printf 'recovery-cleanup RED: %s removed its journal; first_raw=%s retry_raw=%s output=%s\n' \
        "$mode" "$failure_raw_exit" "$raw_exit" "$(cat "$output")" >&2
      return 1
    fi
    assert_journal_stage "$(pending_journal_path "$fixture")" COMMITTED
    grep -Fx "third-party-cleanup-$mode" \
      "$fixture/services/cleanup-$mode-service/third-party.txt" >/dev/null

    set +e
    (cd "$fixture" && bash scripts/create-service.sh --name "cleanup-$mode" \
      --port "$((8330 + index))" --schema "cleanup_${mode_slug}_db") >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -eq 0 ]] || {
      printf 'recovery-cleanup RED: %s retained transaction was not recoverable: %s\n' \
        "$mode" "$(cat "$output")" >&2
      return 1
    }
    grep -Fx "third-party-cleanup-$mode" \
      "$fixture/services/cleanup-$mode-service/third-party.txt" >/dev/null
    assert_no_transaction_artifacts "$fixture"
  done

  fixture="$test_root/recovery-cleanup-concurrent-entry"
  output="$test_root/recovery-cleanup-concurrent-entry.log"
  crash_transaction "$fixture" cleanup-concurrent-entry 8340 \
    cleanup_concurrent_entry_db after-journal-committed "$output"
  committed_before="$(snapshot_state "$fixture")"
  set +e
  (
    cd "$fixture"
    exec env PYTHONPATH="$modules" \
      XZWL_RECOVERY_CLEANUP_SHIM_MODE=concurrent-entry \
      bash scripts/create-service.sh --name cleanup-concurrent-entry --port 8340 \
        --schema cleanup_concurrent_entry_db
  ) >>"$output" 2>&1
  raw_exit=$?
  set -e
  [[ "$raw_exit" -ne 0 ]] || {
    printf 'recovery-cleanup RED: concurrent entry was silently deleted\n' >&2
    return 1
  }
  grep -F 'recovery cleanup conflict; journal retained at' "$output" >/dev/null
  [[ "$committed_before" == "$(snapshot_state "$fixture")" ]] || {
    printf 'recovery-cleanup RED: concurrent entry changed committed repository bytes\n' >&2
    return 1
  }
  local concurrent_journal
  concurrent_journal="$(pending_journal_path "$fixture")"
  assert_journal_stage "$concurrent_journal" RECOVERY_REQUIRED
  grep -Fx 'third-party-during-cleanup' \
    "$(dirname "$concurrent_journal")/third-party-during-cleanup.txt" >/dev/null

  fixture="$test_root/recovery-cleanup-symlink"
  output="$test_root/recovery-cleanup-symlink.log"
  local symlink_target="$test_root/recovery-cleanup-symlink-target.txt"
  printf 'external-symlink-target\n' > "$symlink_target"
  crash_transaction "$fixture" cleanup-symlink 8341 cleanup_symlink_db \
    after-journal-committed "$output"
  local symlink_journal
  symlink_journal="$(pending_journal_path "$fixture")"
  python3 - "$symlink_journal" "$symlink_target" <<'PY'
from pathlib import Path
import sys

backup = Path(sys.argv[1]).parent / "pom.backup"
backup.unlink()
backup.symlink_to(Path(sys.argv[2]))
PY
  (cd "$fixture" && bash scripts/create-service.sh --name cleanup-symlink \
    --port 8341 --schema cleanup_symlink_db) >>"$output" 2>&1
  grep -Fx 'external-symlink-target' "$symlink_target" >/dev/null
  assert_no_transaction_artifacts "$fixture"
}

test_top_level_recovery_rmdir_binds_transaction_inode() {
  local modules="$test_root/top-rmdir-race-shim"
  local failures=0
  local fixture
  local output
  local marker
  local committed_before
  local first_raw
  local retry_raw
  local identity_raw
  mkdir -p "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import inspect
import json
import os
from pathlib import Path
import stat

kind = os.environ.get("XZWL_TOP_RMDIR_RACE_KIND", "")
marker = Path(os.environ.get("XZWL_TOP_RMDIR_RACE_MARKER", "/nonexistent"))
injected = False
real_fsync = os.fsync

def cleanup_locals():
    for frame in inspect.stack():
        if frame.function == "remove_recovered_directory":
            return frame.frame.f_locals
    return None

def guarded_fsync(descriptor):
    global injected
    result = real_fsync(descriptor)
    values = cleanup_locals()
    if injected or not kind or values is None:
        return result
    transaction_fd = values.get("transaction_fd")
    transaction = values["self"]
    in_holding_creation = any(
        frame.function in {"create_cleanup_holding", "create_stable_holding_child"}
        for frame in inspect.stack()
    )
    if in_holding_creation:
        if descriptor not in {transaction.lock_fd, transaction.stable_holding_fd}:
            return result
    else:
        if descriptor != transaction_fd:
            return result
        try:
            os.stat("RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False)
            return result
        except FileNotFoundError:
            pass
    removal_name = values.get("finalize_name") or values["quarantine_name"]
    source = transaction.repo_root / removal_name
    displaced = transaction.repo_root / f".create-service.displaced-by-rmdir-race-{kind}"
    os.rename(source, displaced)
    if kind == "empty":
        source.mkdir(mode=0o700)
    elif kind == "nonempty":
        source.mkdir(mode=0o700)
        (source / "third-party.txt").write_text("third-party-nonempty\n", encoding="utf-8")
    elif kind == "symlink":
        target = transaction.repo_root / ".top-rmdir-third-party-target"
        target.write_text("third-party-symlink-target\n", encoding="utf-8")
        source.symlink_to(target)
    else:
        raise RuntimeError(f"unknown top-level rmdir race kind: {kind}")
    replacement = source.lstat()
    bound = displaced.lstat()
    marker.write_text(
        json.dumps({
            "kind": kind,
            "replacementDevice": replacement.st_dev,
            "replacementInode": replacement.st_ino,
            "replacementMode": replacement.st_mode,
            "transactionDevice": bound.st_dev,
            "transactionInode": bound.st_ino,
            "injectedRemovalName": removal_name,
        }, sort_keys=True),
        encoding="utf-8",
    )
    injected = True
    return result

os.fsync = guarded_fsync
PY

  for kind in empty nonempty symlink; do
    fixture="$test_root/top-rmdir-race-$kind"
    output="$test_root/top-rmdir-race-$kind.log"
    marker="$test_root/top-rmdir-race-$kind.marker"
    crash_transaction "$fixture" "top-rmdir-$kind" 8342 \
      "top_rmdir_${kind}_db" after-journal-committed "$output"
    printf 'third-party-target-%s\n' "$kind" \
      > "$fixture/services/top-rmdir-$kind-service/third-party.txt"
    committed_before="$(snapshot_state "$fixture")"

    set +e
    (
      cd "$fixture"
      exec env PYTHONPATH="$modules" \
        XZWL_TOP_RMDIR_RACE_KIND="$kind" \
        XZWL_TOP_RMDIR_RACE_MARKER="$marker" \
        bash scripts/create-service.sh --name "top-rmdir-$kind" --port 8342 \
          --schema "top_rmdir_${kind}_db"
    ) >>"$output" 2>&1
    first_raw=$?
    set -e
    if [[ "$first_raw" -eq 0 ]]; then
      printf 'top-rmdir-identity RED: %s pathname substitution returned raw0\n' \
        "$kind" >&2
      failures=$((failures + 1))
    fi
    if [[ ! -f "$marker" ]]; then
      printf 'top-rmdir-identity RED: %s test seam did not reach final removal\n' \
        "$kind" >&2
      failures=$((failures + 1))
      continue
    fi
    if [[ "$committed_before" != "$(snapshot_state "$fixture")" ]]; then
      printf 'top-rmdir-identity RED: %s race changed committed metadata/target bytes\n' \
        "$kind" >&2
      failures=$((failures + 1))
    fi
    set +e
    python3 - "$fixture" "$marker" failure <<'PY'
import json
from pathlib import Path
import stat
import sys

root = Path(sys.argv[1])
data = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
phase = sys.argv[3]

def find_identity(device, inode):
    matches = []
    for candidate in root.iterdir():
        try:
            info = candidate.lstat()
        except FileNotFoundError:
            continue
        if (info.st_dev, info.st_ino) == (device, inode):
            matches.append(candidate)
    if len(matches) != 1:
        raise SystemExit(f"identity {device}:{inode} has {len(matches)} paths")
    return matches[0]

replacement = find_identity(data["replacementDevice"], data["replacementInode"])
kind = data["kind"]
if kind == "empty":
    if not replacement.is_dir() or replacement.is_symlink() or list(replacement.iterdir()):
        raise SystemExit("third-party empty directory changed")
elif kind == "nonempty":
    if (replacement / "third-party.txt").read_text(encoding="utf-8") != "third-party-nonempty\n":
        raise SystemExit("third-party nonempty directory bytes changed")
elif kind == "symlink":
    if not replacement.is_symlink():
        raise SystemExit("third-party symlink type changed")
    target = replacement.readlink()
    if not target.is_absolute():
        target = replacement.parent / target
    if target.read_text(encoding="utf-8") != "third-party-symlink-target\n":
        raise SystemExit("third-party symlink target changed")

transaction_matches = []
stable_roots = list(root.parent.glob(".xzwl-create-service-holding-*"))
candidates = [*root.iterdir(), *root.rglob("*")]
for stable_root in stable_roots:
    candidates.extend([stable_root, *stable_root.rglob("*")])
for candidate in candidates:
    try:
        info = candidate.lstat()
    except FileNotFoundError:
        continue
    if (info.st_dev, info.st_ino) == (
        data["transactionDevice"], data["transactionInode"]
    ):
        transaction_matches.append(candidate)
if phase == "failure":
    if len(transaction_matches) != 1:
        raise SystemExit("bound transaction inode was lost or duplicated")
    transaction = transaction_matches[0]
    scanner = transaction if transaction.name.startswith(".create-service.") else transaction.parent
    if not scanner.name.startswith(".create-service."):
        raise SystemExit("bound transaction was not registered below a recovery scanner path")
    journal = scanner / "RECOVERY.json"
    if not journal.is_file():
        raise SystemExit("bound transaction lacks a parseable recovery journal")
    record = json.loads(journal.read_text(encoding="utf-8"))
    if record.get("stage") != "COMMITTED":
        raise SystemExit("recovered transaction journal is not COMMITTED")
else:
    if transaction_matches:
        raise SystemExit("retry left the bound transaction inode visible")

for candidate in root.glob(".create-service.*"):
    if not candidate.is_dir() or candidate.is_symlink():
        raise SystemExit(f"unowned scanner artifact remains: {candidate.name}")
    if not (candidate / "RECOVERY.json").is_file():
        raise SystemExit(f"journal-less scanner artifact remains: {candidate.name}")
PY
    identity_raw=$?
    set -e
    if [[ "$identity_raw" -ne 0 ]]; then
      printf 'top-rmdir-identity RED: %s replacement/evidence identity check failed\n' \
        "$kind" >&2
      failures=$((failures + 1))
    fi

    set +e
    (cd "$fixture" && bash scripts/create-service.sh --name "top-rmdir-$kind" \
      --port 8342 --schema "top_rmdir_${kind}_db") >>"$output" 2>&1
    retry_raw=$?
    set -e
    if [[ "$retry_raw" -ne 0 ]]; then
      printf 'top-rmdir-identity RED: %s retry_raw=%s output=%s\n' \
        "$kind" "$retry_raw" "$(cat "$output")" >&2
      failures=$((failures + 1))
      continue
    fi
    set +e
    python3 - "$fixture" "$marker" retry <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
data = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
identities = {
    (candidate.lstat().st_dev, candidate.lstat().st_ino): candidate
    for candidate in root.iterdir()
}
replacement = identities.get((data["replacementDevice"], data["replacementInode"]))
if replacement is None:
    raise SystemExit("retry lost the third-party replacement")
kind = data["kind"]
if kind == "empty":
    if not replacement.is_dir() or replacement.is_symlink() or list(replacement.iterdir()):
        raise SystemExit("retry changed the third-party empty directory")
elif kind == "nonempty":
    if (replacement / "third-party.txt").read_text(encoding="utf-8") != "third-party-nonempty\n":
        raise SystemExit("retry changed third-party nonempty bytes")
elif kind == "symlink":
    if not replacement.is_symlink():
        raise SystemExit("retry changed the third-party symlink type")
    target = replacement.readlink()
    if not target.is_absolute():
        target = replacement.parent / target
    if target.read_text(encoding="utf-8") != "third-party-symlink-target\n":
        raise SystemExit("retry changed the third-party symlink target")
if (data["transactionDevice"], data["transactionInode"]) in identities:
    raise SystemExit("retry left the recovered transaction inode visible")
for candidate in root.glob(".create-service.*"):
    raise SystemExit(f"retry left transaction artifact: {candidate.name}")
PY
    identity_raw=$?
    set -e
    if [[ "$identity_raw" -ne 0 ]]; then
      printf 'top-rmdir-identity RED: %s retry did not preserve/clean identities\n' \
        "$kind" >&2
      failures=$((failures + 1))
    fi
    grep -Fx "third-party-target-$kind" \
      "$fixture/services/top-rmdir-$kind-service/third-party.txt" >/dev/null
  done
  [[ "$failures" -eq 0 ]] || return 1
}

test_top_level_recovery_rmdir_has_no_post_stat_window() {
  local kind fixture output hooks ready source displaced child_pid raw_exit identity_raw
  local committed_before
  local failures=0

  for kind in empty nonempty symlink; do
    fixture="$test_root/top-rmdir-window-$kind"
    output="$test_root/top-rmdir-window-$kind.log"
    hooks="$test_root/top-rmdir-window-$kind-hooks"
    crash_transaction "$fixture" "top-window-$kind" 8343 \
      "top_window_${kind}_db" after-journal-committed "$output"
    committed_before="$(snapshot_state "$fixture")"
    mkdir -p "$hooks"

    (
      cd "$fixture"
      exec env \
        XZWL_CREATE_SERVICE_TEST_MODE=1 \
        XZWL_CREATE_SERVICE_TEST_HOOK_DIR="$hooks" \
        XZWL_CREATE_SERVICE_TEST_HOOK_POINTS=before-top-level-holding-claim \
        bash scripts/create-service.sh --name "top-window-$kind" --port 8343 \
          --schema "top_window_${kind}_db"
    ) >>"$output" 2>&1 &
    child_pid=$!
    ready="$hooks/before-top-level-holding-claim.ready"
    wait_for_test_path "$ready" "$child_pid" || {
      printf 'top-rmdir-window RED: %s did not reach the post-stat barrier\n' "$kind" >&2
      kill "$child_pid" 2>/dev/null || true
      wait "$child_pid" 2>/dev/null || true
      return 1
    }
    source="$(find "$fixture" -maxdepth 1 -type d \
      -name '.create-service.cleanup-finalize-*' -print -quit)"
    [[ -n "$source" ]] || {
      printf 'top-rmdir-window RED: %s final cleanup basename was not visible\n' "$kind" >&2
      kill "$child_pid" 2>/dev/null || true
      wait "$child_pid" 2>/dev/null || true
      return 1
    }
    displaced="$fixture/.create-service.displaced-by-rmdir-window-$kind"
    mv "$source" "$displaced"
    case "$kind" in
      empty) mkdir "$source" ;;
      nonempty)
        mkdir "$source"
        printf 'third-party-nonempty\n' > "$source/third-party.txt"
        ;;
      symlink)
        printf 'third-party-symlink-target\n' \
          > "$fixture/.top-rmdir-window-third-party-target"
        ln -s "$fixture/.top-rmdir-window-third-party-target" "$source"
        ;;
    esac
    python3 - "$source" "$hooks/replacement.json" <<'PY'
import json
from pathlib import Path
import sys

info = Path(sys.argv[1]).lstat()
Path(sys.argv[2]).write_text(json.dumps({
    "device": info.st_dev,
    "inode": info.st_ino,
    "mode": info.st_mode,
}), encoding="utf-8")
PY
    touch "$hooks/before-top-level-holding-claim.continue"
    set +e
    wait "$child_pid"
    raw_exit=$?
    set -e
    [[ "$raw_exit" -ne 0 ]] || {
      printf 'top-rmdir-window RED: %s pathname substitution returned raw0\n' "$kind" >&2
      failures=$((failures + 1))
    }
    grep -F 'top-level cleanup identity changed before private holding claim' \
      "$output" >/dev/null || {
      printf 'top-rmdir-window RED: %s lacked the holding identity diagnostic\n' "$kind" >&2
      failures=$((failures + 1))
    }
    [[ "$committed_before" == "$(snapshot_state "$fixture")" ]] || {
      printf 'top-rmdir-window RED: %s changed committed metadata or target bytes\n' \
        "$kind" >&2
      failures=$((failures + 1))
    }
    set +e
    python3 - "$fixture" "$hooks/replacement.json" "$kind" \
      "$(stable_holding_root "$fixture")" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
identity = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
kind = sys.argv[3]
holding_root = Path(sys.argv[4])
matches = []
for candidate in root.iterdir():
    try:
        info = candidate.lstat()
    except FileNotFoundError:
        continue
    if (info.st_dev, info.st_ino) == (identity["device"], identity["inode"]):
        matches.append(candidate)
if len(matches) != 1:
    raise SystemExit(f"third-party {kind} identity has {len(matches)} paths")
replacement = matches[0]
if kind == "empty":
    if replacement.is_symlink() or not replacement.is_dir() or list(replacement.iterdir()):
        raise SystemExit("third-party empty directory changed")
elif kind == "nonempty":
    if (replacement / "third-party.txt").read_text(encoding="utf-8") != "third-party-nonempty\n":
        raise SystemExit("third-party nonempty bytes changed")
elif kind == "symlink":
    if not replacement.is_symlink():
        raise SystemExit("third-party symlink type changed")
    target = replacement.readlink()
    if not target.is_absolute():
        target = replacement.parent / target
    if target.read_text(encoding="utf-8") != "third-party-symlink-target\n":
        raise SystemExit("third-party symlink target changed")
else:
    raise SystemExit(f"unknown kind: {kind}")
journals = list(root.glob(".create-service.*/RECOVERY.json"))
if holding_root.exists() and not holding_root.is_symlink():
    journals.extend(holding_root.glob(".create-service.*/RECOVERY.json"))
if len(journals) != 1:
    retained = list(holding_root.rglob("*")) if holding_root.exists() else []
    raise SystemExit(
        f"expected one scanner journal, found {len(journals)}; "
        f"holding_root={holding_root} retained={retained}"
    )
PY
    identity_raw=$?
    set -e
    if [[ "$identity_raw" -ne 0 ]]; then
      printf 'top-rmdir-window RED: %s post-stat replacement was deleted or evidence was lost\n' \
        "$kind" >&2
      failures=$((failures + 1))
      continue
    fi
    set +e
    (cd "$fixture" && bash scripts/create-service.sh --name "top-window-$kind" \
      --port 8343 --schema "top_window_${kind}_db") >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -eq 0 ]] || {
      printf 'top-rmdir-window RED: %s retained transaction retry raw=%s: %s\n' \
        "$kind" "$raw_exit" "$(cat "$output")" >&2
      failures=$((failures + 1))
    }
    [[ "$committed_before" == "$(snapshot_state "$fixture")" ]] || {
      printf 'top-rmdir-window RED: %s retry changed committed metadata or target bytes\n' \
        "$kind" >&2
      failures=$((failures + 1))
    }
    assert_no_transaction_artifacts "$fixture"
  done
  [[ "$failures" -eq 0 ]] || return 1
}

test_stable_holding_root_is_never_removed() {
  local modules="$test_root/stable-holding-root-rmdir-shim"
  local kind fixture output marker holding_root child_pid raw_exit failures=0
  mkdir -p "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import inspect
import json
import os
from pathlib import Path

kind = os.environ.get("XZWL_HOLDING_ROOT_RMDIR_KIND", "")
marker = Path(os.environ.get("XZWL_HOLDING_ROOT_RMDIR_MARKER", "/nonexistent"))
real_rmdir = os.rmdir
injected = False

def guarded_rmdir(path, *args, dir_fd=None):
    global injected
    name = os.fsdecode(path)
    frames = inspect.stack()
    target = next(
        (frame for frame in frames if frame.function == "remove_bound_cleanup_through_holding"),
        None,
    )
    if (
        not injected
        and kind
        and target is not None
        and name.startswith(".create-service.cleanup-holding-")
    ):
        transaction = target.frame.f_locals["self"]
        source = transaction.repo_root / name
        displaced = transaction.repo_root / f".create-service.displaced-stable-root-{kind}"
        os.rename(source, displaced)
        if kind == "empty":
            source.mkdir(mode=0o700)
        elif kind == "nonempty":
            source.mkdir(mode=0o700)
            (source / "third-party.txt").write_text("third-party-nonempty\n", encoding="utf-8")
        elif kind == "symlink":
            target_path = transaction.repo_root / ".stable-holding-third-party-target"
            target_path.write_text("third-party-symlink-target\n", encoding="utf-8")
            source.symlink_to(target_path)
        else:
            raise RuntimeError(f"unknown holding-root rmdir kind: {kind}")
        info = source.lstat()
        marker.write_text(json.dumps({
            "kind": kind,
            "device": info.st_dev,
            "inode": info.st_ino,
            "mode": info.st_mode,
            "source": str(source),
            "displaced": str(displaced),
        }, sort_keys=True), encoding="utf-8")
        injected = True
    return real_rmdir(path, *args, dir_fd=dir_fd)

os.rmdir = guarded_rmdir
PY

  for kind in empty nonempty symlink; do
    fixture="$test_root/stable-holding-root-rmdir-$kind"
    output="$test_root/stable-holding-root-rmdir-$kind.log"
    marker="$test_root/stable-holding-root-rmdir-$kind.json"
    crash_transaction "$fixture" "stable-root-$kind" 8344 \
      "stable_root_${kind}_db" after-journal-committed "$output"
    holding_root="$(stable_holding_root "$fixture")"
    python3 - "$holding_root" "$test_root/stable-root-$kind.before" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
output = Path(sys.argv[2])
if root.exists() and not root.is_symlink():
    info = root.stat()
    output.write_text(f"{info.st_dev}:{info.st_ino}\n", encoding="ascii")
PY
    set +e
    (
      cd "$fixture"
      exec env PYTHONPATH="$modules" \
        XZWL_HOLDING_ROOT_RMDIR_KIND="$kind" \
        XZWL_HOLDING_ROOT_RMDIR_MARKER="$marker" \
        bash scripts/create-service.sh --name "stable-root-$kind" --port 8344 \
          --schema "stable_root_${kind}_db"
    ) >>"$output" 2>&1
    raw_exit=$?
    set -e
    if [[ -f "$marker" ]]; then
      printf 'stable-holding-root RED: %s public holding root reached rmdir, raw=%s\n' \
        "$kind" "$raw_exit" >&2
      failures=$((failures + 1))
      continue
    fi
    [[ "$raw_exit" -eq 0 ]] || {
      printf 'stable-holding-root RED: %s recovery failed: raw=%s output=%s\n' \
        "$kind" "$raw_exit" "$(cat "$output")" >&2
      failures=$((failures + 1))
      continue
    }
    [[ -d "$holding_root" && ! -L "$holding_root" ]] || {
      printf 'stable-holding-root RED: %s stable root was not retained\n' "$kind" >&2
      failures=$((failures + 1))
      continue
    }
    local after_identity before_identity
    after_identity="$(python3 - "$holding_root" <<'PY'
from pathlib import Path
import sys
info = Path(sys.argv[1]).stat()
print(f"{info.st_dev}:{info.st_ino}")
PY
)"
    before_identity="$(cat "$test_root/stable-root-$kind.before")"
    [[ -n "$before_identity" && "$before_identity" == "$after_identity" ]] || {
      printf 'stable-holding-root RED: %s stable root inode changed\n' "$kind" >&2
      failures=$((failures + 1))
    }
    assert_no_transaction_artifacts "$fixture"
  done
  [[ "$failures" -eq 0 ]] || return 1
}

test_holding_initialization_is_failure_atomic() {
  local modules="$test_root/holding-init-fault-shim"
  local mode fixture output marker committed_before raw_exit retry_raw holding_root failures=0
  mkdir -p "$modules"
  cat > "$modules/sitecustomize.py" <<'PY'
import errno
import inspect
import os
from pathlib import Path

mode = os.environ.get("XZWL_HOLDING_INIT_FAULT", "")
marker = Path(os.environ.get("XZWL_HOLDING_INIT_MARKER", "/nonexistent"))
real_open = os.open
real_fsync = os.fsync
injected = False

def creation_frame():
    for frame in inspect.stack():
        if frame.function in {"create_cleanup_holding", "create_stable_holding_child"}:
            return frame
    return None

def record_and_raise(point):
    global injected
    marker.write_text(point + "\n", encoding="utf-8")
    injected = True
    raise OSError(errno.EIO, f"injected stable holding initialization {point} failure")

def guarded_open(path, flags, mode_bits=0o777, *, dir_fd=None):
    if (
        not injected
        and mode == "journal-write"
        and creation_frame() is not None
        and os.fsdecode(path).startswith(".RECOVERY.")
        and os.fsdecode(path).endswith(".tmp")
        and flags & os.O_CREAT
    ):
        record_and_raise("journal-write")
    return real_open(path, flags, mode_bits, dir_fd=dir_fd)

def guarded_fsync(descriptor):
    if injected:
        return real_fsync(descriptor)
    frame = creation_frame()
    if frame is None:
        return real_fsync(descriptor)
    values = frame.frame.f_locals
    functions = {item.function for item in inspect.stack()}
    if mode == "file-fsync" and "write_new_at" in functions:
        record_and_raise("file-fsync")
    child_fd = values.get("holding_fd", values.get("child_fd"))
    if mode == "child-fsync" and descriptor == child_fd and "write_new_at" not in functions:
        record_and_raise("child-fsync")
    transaction = values.get("self")
    root_candidates = {
        getattr(transaction, "lock_fd", None),
        getattr(transaction, "stable_holding_fd", None),
    }
    if mode == "root-fsync" and descriptor in root_candidates and descriptor != child_fd:
        record_and_raise("root-fsync")
    return real_fsync(descriptor)

os.open = guarded_open
os.fsync = guarded_fsync
PY

  for mode in journal-write file-fsync child-fsync root-fsync; do
    fixture="$test_root/holding-init-$mode"
    output="$test_root/holding-init-$mode.log"
    marker="$test_root/holding-init-$mode.marker"
    crash_transaction "$fixture" "holding-init-$mode" 8345 \
      "holding_init_${mode//-/_}_db" after-journal-committed "$output"
    committed_before="$(snapshot_state "$fixture")"
    set +e
    (
      cd "$fixture"
      exec env PYTHONPATH="$modules" \
        XZWL_HOLDING_INIT_FAULT="$mode" \
        XZWL_HOLDING_INIT_MARKER="$marker" \
        bash scripts/create-service.sh --name "holding-init-$mode" --port 8345 \
          --schema "holding_init_${mode//-/_}_db"
    ) >>"$output" 2>&1
    raw_exit=$?
    set -e
    [[ "$raw_exit" -ne 0 && -f "$marker" ]] || {
      printf 'holding-init RED: %s fault was not observed: raw=%s output=%s\n' \
        "$mode" "$raw_exit" "$(cat "$output")" >&2
      failures=$((failures + 1))
      continue
    }
    [[ "$committed_before" == "$(snapshot_state "$fixture")" ]] || {
      printf 'holding-init RED: %s changed committed metadata or target bytes\n' "$mode" >&2
      failures=$((failures + 1))
    }
    set +e
    python3 - "$fixture" "$(stable_holding_root "$fixture")" <<'PY'
from pathlib import Path
import sys

repo = Path(sys.argv[1])
holding = Path(sys.argv[2])
for candidate in repo.glob(".create-service.*"):
    if candidate.is_dir() and not candidate.is_symlink() and not (candidate / "RECOVERY.json").is_file():
        raise SystemExit(f"journal-less repository scanner artifact: {candidate.name}")
if holding.exists():
    if holding.is_symlink() or not holding.is_dir():
        raise SystemExit("stable holding root changed type")
    for child in holding.iterdir():
        if child.is_symlink() or not child.is_dir() or not (child / "RECOVERY.json").is_file():
            raise SystemExit(f"journal-less stable holding child: {child.name}")
PY
    local evidence_raw=$?
    set -e
    if [[ "$evidence_raw" -ne 0 ]]; then
      printf 'holding-init RED: %s left a journal-less scanner artifact\n' "$mode" >&2
      failures=$((failures + 1))
      continue
    fi
    set +e
    (cd "$fixture" && bash scripts/create-service.sh --name "holding-init-$mode" \
      --port 8345 --schema "holding_init_${mode//-/_}_db") >>"$output" 2>&1
    retry_raw=$?
    set -e
    [[ "$retry_raw" -eq 0 ]] || {
      printf 'holding-init RED: %s retry failed: raw=%s output=%s\n' \
        "$mode" "$retry_raw" "$(cat "$output")" >&2
      failures=$((failures + 1))
      continue
    }
    [[ "$committed_before" == "$(snapshot_state "$fixture")" ]] || {
      printf 'holding-init RED: %s retry changed committed bytes\n' "$mode" >&2
      failures=$((failures + 1))
    }
    assert_no_transaction_artifacts "$fixture"
  done
  [[ "$failures" -eq 0 ]] || return 1
}

test_stable_holding_root_preflight() {
  local case fixture output holding_root before raw_exit failures=0
  for case in symlink regular mode owner device parent-unwritable cross-filesystem; do
    fixture="$test_root/stable-root-preflight-$case"
    output="$test_root/stable-root-preflight-$case.log"
    make_fixture "$fixture"
    holding_root="$(stable_holding_root "$fixture")"
    case "$case" in
      symlink) ln -s "$fixture/pom.xml" "$holding_root" ;;
      regular) printf 'third-party-root\n' > "$holding_root" ;;
      mode) mkdir -m 0755 "$holding_root" ;;
    esac
    before="$(snapshot_state "$fixture")"
    set +e
    (
      cd "$fixture"
      XZWL_CREATE_SERVICE_TEST_FAULTS="stable-root-$case" \
        bash scripts/create-service.sh --name "preflight-$case" --port 8346 \
          --schema "preflight_${case//-/_}_db"
    ) >"$output" 2>&1
    raw_exit=$?
    set -e
    if [[ "$raw_exit" -eq 0 ]]; then
      printf 'stable-root-preflight RED: %s mismatch was accepted\n' "$case" >&2
      failures=$((failures + 1))
      continue
    fi
    grep -F 'stable holding root' "$output" >/dev/null || {
      printf 'stable-root-preflight RED: %s lacked explicit diagnostic: %s\n' \
        "$case" "$(cat "$output")" >&2
      failures=$((failures + 1))
    }
    [[ "$before" == "$(snapshot_state "$fixture")" ]] || {
      printf 'stable-root-preflight RED: %s changed repository bytes\n' "$case" >&2
      failures=$((failures + 1))
    }
  done

  fixture="$test_root/stable-root-reuse"
  make_fixture "$fixture"
  holding_root="$(stable_holding_root "$fixture")"
  (cd "$fixture" && bash scripts/create-service.sh --name stable-one --port 8347 \
    --schema stable_one_db)
  local first_identity second_identity
  first_identity="$(python3 - "$holding_root" <<'PY'
from pathlib import Path
import sys
info = Path(sys.argv[1]).stat()
print(f"{info.st_dev}:{info.st_ino}")
PY
)"
  assert_no_transaction_artifacts "$fixture"
  (cd "$fixture" && bash scripts/create-service.sh --name stable-two --port 8348 \
    --schema stable_two_db)
  second_identity="$(python3 - "$holding_root" <<'PY'
from pathlib import Path
import sys
info = Path(sys.argv[1]).stat()
print(f"{info.st_dev}:{info.st_ino}")
PY
)"
  [[ "$first_identity" == "$second_identity" ]] || {
    printf 'stable-root-preflight RED: stable root was not reused\n' >&2
    failures=$((failures + 1))
  }
  assert_no_transaction_artifacts "$fixture"
  [[ "$failures" -eq 0 ]] || return 1
}

run_quality_case() {
  case "$1" in
    java-keywords) test_java_package_keywords ;;
    rollback) test_rollback_continues_after_restore_failure ;;
    target-race) test_target_publish_race ;;
    metadata-race) test_metadata_snapshot_race ;;
    concurrency) test_concurrency_barrier_and_global_lock ;;
    lock-race) test_lock_symlink_open_race ;;
    signal-before) test_signal_before_final_publish_rolls_back ;;
    signal-after) test_signal_after_commit_preserves_third_party ;;
    signal-deferred) test_signal_is_deferred_across_metadata_to_publish ;;
    late-target-race) test_target_race_after_metadata_commit_rolls_back_metadata_only ;;
    stable-lock) test_stable_repo_directory_lock ;;
    lock-cleanup) test_lock_cleanup_never_unlinks_replaced_path ;;
    target-noreplace) test_target_publish_is_native_no_replace ;;
    metadata-exchange) test_metadata_exchange_detects_final_compare_race ;;
    metadata-rollback) test_metadata_rollback_preserves_post_exchange_updates ;;
    crash-recovery) test_abrupt_exit_recovery_matrix ;;
    target-parent-fsync) test_target_parent_fsync_precedes_commit ;;
    target-parent-binding) test_target_parent_inode_is_bound_during_fsync ;;
    native-probe) test_native_filesystem_probe_precedes_recovery_and_staging ;;
    native-probe-stable) test_native_probe_uses_crash_recoverable_stable_root ;;
    native-probe-ownership) test_native_probe_creation_preserves_unowned_directory ;;
    native-probe-empty-ownership) test_native_probe_recovery_preserves_empty_markerless_directory ;;
    native-probe-cleanup-atomic) test_native_probe_cleanup_atomically_preserves_replacements ;;
    dirfd-native) test_native_operations_are_dirfd_relative ;;
    stage-manual) test_recovery_required_is_manual_only ;;
    stage-committed) test_committed_recovery_conflicts_are_preserved ;;
    stage-matrix) test_stage_driven_recovery_matrix ;;
    metadata-nonregular) test_metadata_exchange_compensates_nonregular_entries ;;
    metadata-slot-race) test_metadata_exchange_compensation_detects_slot_replacement ;;
    cleanup-fd) test_recovery_cleanup_owns_each_descriptor_once ;;
    top-rmdir-identity) test_top_level_recovery_rmdir_binds_transaction_inode ;;
    top-rmdir-window) test_top_level_recovery_rmdir_has_no_post_stat_window ;;
    stable-holding-root) test_stable_holding_root_is_never_removed ;;
    holding-init) test_holding_initialization_is_failure_atomic ;;
    stable-root-preflight) test_stable_holding_root_preflight ;;
    pom-comment) test_pom_comment_preservation ;;
    catalog-strict) test_strict_catalog_validation ;;
    strict-metadata) test_strict_metadata_and_pom_comment_preservation ;;
    *) printf 'unknown XZWL_TEST_CREATE_SERVICE_CASE: %s\n' "$1" >&2; exit 2 ;;
  esac
}

if [[ -n "${XZWL_TEST_CREATE_SERVICE_CASE:-}" ]]; then
  run_quality_case "$XZWL_TEST_CREATE_SERVICE_CASE"
  printf 'service generator quality case passed: %s\n' "$XZWL_TEST_CREATE_SERVICE_CASE"
  exit 0
fi

test_java_package_keywords
test_rollback_continues_after_restore_failure
test_target_publish_race
test_metadata_snapshot_race
test_concurrency_barrier_and_global_lock
test_lock_symlink_open_race
test_signal_before_final_publish_rolls_back
test_signal_after_commit_preserves_third_party
test_signal_is_deferred_across_metadata_to_publish
test_target_race_after_metadata_commit_rolls_back_metadata_only
test_stable_repo_directory_lock
test_lock_cleanup_never_unlinks_replaced_path
test_target_publish_is_native_no_replace
test_metadata_exchange_detects_final_compare_race
test_metadata_rollback_preserves_post_exchange_updates
test_abrupt_exit_recovery_matrix
test_target_parent_fsync_precedes_commit
test_target_parent_inode_is_bound_during_fsync
test_native_filesystem_probe_precedes_recovery_and_staging
test_native_probe_uses_crash_recoverable_stable_root
test_native_probe_creation_preserves_unowned_directory
test_native_probe_recovery_preserves_empty_markerless_directory
test_native_probe_cleanup_atomically_preserves_replacements
test_native_operations_are_dirfd_relative
test_recovery_required_is_manual_only
test_committed_recovery_conflicts_are_preserved
test_stage_driven_recovery_matrix
test_metadata_exchange_compensates_nonregular_entries
test_metadata_exchange_compensation_detects_slot_replacement
test_recovery_cleanup_owns_each_descriptor_once
test_top_level_recovery_rmdir_binds_transaction_inode
test_top_level_recovery_rmdir_has_no_post_stat_window
test_stable_holding_root_is_never_removed
test_holding_initialization_is_failure_atomic
test_stable_holding_root_preflight
test_strict_metadata_and_pom_comment_preservation

missing_fcntl_fixture="$test_root/missing-fcntl"
make_fixture "$missing_fcntl_fixture"
missing_fcntl_modules="$test_root/missing-fcntl-modules"
mkdir -p "$missing_fcntl_modules"
printf '%s\n' \
  'import builtins' \
  '_real_import = builtins.__import__' \
  'def _without_fcntl(name, *args, **kwargs):' \
  '    if name == "fcntl":' \
  '        raise ImportError("simulated missing POSIX fcntl")' \
  '    return _real_import(name, *args, **kwargs)' \
  'builtins.__import__ = _without_fcntl' \
  > "$missing_fcntl_modules/sitecustomize.py"
missing_fcntl_before="$(snapshot_state "$missing_fcntl_fixture")"
set +e
missing_fcntl_output="$(
  cd "$missing_fcntl_fixture"
  PYTHONPATH="$missing_fcntl_modules" bash scripts/create-service.sh \
    --name no-fcntl --port 8210 --schema no_fcntl_db 2>&1
)"
missing_fcntl_exit=$?
set -e
missing_fcntl_after="$(snapshot_state "$missing_fcntl_fixture")"
missing_fcntl_artifact=false
if [[ -e "$missing_fcntl_fixture/.create-service.lock" \
  || -L "$missing_fcntl_fixture/.create-service.lock" ]]; then
  missing_fcntl_artifact=true
fi
if [[ "$missing_fcntl_exit" -eq 0 \
  || "$missing_fcntl_output" != *'Python 3.9+ with POSIX fcntl support is required by create-service.sh'* \
  || "$missing_fcntl_before" != "$missing_fcntl_after" \
  || "$missing_fcntl_artifact" != false ]]; then
  printf 'missing-fcntl preflight regression: raw=%s explicit_diagnostic=%s repo_unchanged=%s lock_artifact=%s output=%s\n' \
    "$missing_fcntl_exit" \
    "$([[ "$missing_fcntl_output" == *'Python 3.9+ with POSIX fcntl support is required by create-service.sh'* ]] && printf true || printf false)" \
    "$([[ "$missing_fcntl_before" == "$missing_fcntl_after" ]] && printf true || printf false)" \
    "$missing_fcntl_artifact" \
    "$missing_fcntl_output" >&2
  exit 1
fi
assert_no_transaction_artifacts "$missing_fcntl_fixture"

missing_nofollow_fixture="$test_root/missing-onofollow"
make_fixture "$missing_nofollow_fixture"
missing_nofollow_modules="$test_root/missing-onofollow-modules"
mkdir -p "$missing_nofollow_modules"
printf '%s\n' \
  'import os' \
  'if hasattr(os, "O_NOFOLLOW"):' \
  '    del os.O_NOFOLLOW' \
  > "$missing_nofollow_modules/sitecustomize.py"
missing_nofollow_before="$(snapshot_state "$missing_nofollow_fixture")"
set +e
missing_nofollow_output="$(
  cd "$missing_nofollow_fixture"
  PYTHONPATH="$missing_nofollow_modules" bash scripts/create-service.sh \
    --name no-nofollow --port 8210 --schema no_nofollow_db 2>&1
)"
missing_nofollow_exit=$?
set -e
[[ "$missing_nofollow_exit" -ne 0 \
  && "$missing_nofollow_output" == *'POSIX O_NOFOLLOW support is required by create-service.sh'* \
  && "$missing_nofollow_before" == "$(snapshot_state "$missing_nofollow_fixture")" ]] || {
  printf 'missing-O_NOFOLLOW preflight regression: raw=%s output=%s\n' \
    "$missing_nofollow_exit" "$missing_nofollow_output" >&2
  exit 1
}
assert_no_transaction_artifacts "$missing_nofollow_fixture"

missing_native_fixture="$test_root/missing-native-rename"
make_fixture "$missing_native_fixture"
missing_native_modules="$test_root/missing-native-modules"
mkdir -p "$missing_native_modules"
printf '%s\n' \
  'import sys' \
  'sys.platform = "unsupported-test-platform"' \
  > "$missing_native_modules/sitecustomize.py"
missing_native_before="$(snapshot_state "$missing_native_fixture")"
set +e
missing_native_output="$(
  cd "$missing_native_fixture"
  PYTHONPATH="$missing_native_modules" bash scripts/create-service.sh \
    --name no-native --port 8210 --schema no_native_db 2>&1
)"
missing_native_exit=$?
set -e
[[ "$missing_native_exit" -ne 0 \
  && "$missing_native_output" == *'Linux renameat2 or Darwin renameatx_np with POSIX O_DIRECTORY and pthread_sigmask support is required by create-service.sh'* \
  && "$missing_native_before" == "$(snapshot_state "$missing_native_fixture")" ]] || {
  printf 'missing-native-rename preflight regression: raw=%s output=%s\n' \
    "$missing_native_exit" "$missing_native_output" >&2
  exit 1
}
assert_no_transaction_artifacts "$missing_native_fixture"

main_fixture="$test_root/main repo with spaces"
make_fixture "$main_fixture"

expect_failure_preserves "$main_fixture" 'missing required argument --port' \
  --name user --schema user_db
expect_failure_preserves "$main_fixture" 'missing required argument --name' \
  --port 8211 --schema user_db
expect_failure_preserves "$main_fixture" 'missing required argument --schema' \
  --name user --port 8211
expect_failure_preserves "$main_fixture" 'missing value for --name' --name
expect_failure_preserves "$main_fixture" 'missing value for --port' --port
expect_failure_preserves "$main_fixture" 'missing value for --schema' --schema
expect_failure_preserves "$main_fixture" 'unknown argument: --owner' \
  --name user --port 8211 --schema user_db --owner platform-team
expect_failure_preserves "$main_fixture" 'duplicate argument: --name' \
  --name user --name other --port 8211 --schema user_db
expect_failure_preserves "$main_fixture" 'duplicate argument: --port' \
  --name user --port 8211 --port 8212 --schema user_db
expect_failure_preserves "$main_fixture" 'duplicate argument: --schema' \
  --name user --port 8211 --schema user_db --schema other_db

for invalid_name in User ../escape /absolute user/child -user user- user--api user_name 'user name' class int; do
  expect_failure_preserves "$main_fixture" 'invalid service name' \
    --name "$invalid_name" --port 8211 --schema user_db
done
[[ ! -e "$main_fixture/escape-service" && ! -e "$test_root/escape-service" ]]

for invalid_port in 1023 65536 -1 +8211 82x1 8211.0 0x2013; do
  expect_failure_preserves "$main_fixture" 'invalid service port' \
    --name user --port "$invalid_port" --schema user_db
done

for invalid_schema in User_db user user-db ../user_db /tmp/user_db _db 'user db' 'user;drop_db'; do
  expect_failure_preserves "$main_fixture" 'invalid service schema' \
    --name user --port 8211 --schema "$invalid_schema"
done

expect_failure_preserves "$main_fixture" 'port already registered: 8200' \
  --name gateway-copy --port 8200 --schema gateway_copy_db

mkdir -p "$main_fixture/services/existing-service"
printf 'do-not-overwrite\n' > "$main_fixture/services/existing-service/sentinel.txt"
expect_failure_preserves "$main_fixture" 'target service already exists' \
  --name existing --port 8212 --schema existing_db

ln -s "$test_root" "$main_fixture/services/symlink-service"
expect_failure_preserves "$main_fixture" 'target service already exists' \
  --name symlink --port 8213 --schema symlink_db

module_fixture="$test_root/duplicate-module"
make_fixture "$module_fixture"
python3 - "$module_fixture/pom.xml" <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
import sys

path = Path(sys.argv[1])
ET.register_namespace("", "http://maven.apache.org/POM/4.0.0")
tree = ET.parse(path)
root = tree.getroot()
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
modules = root.find("m:modules", namespace)
ET.SubElement(modules, "{http://maven.apache.org/POM/4.0.0}module").text = "services/component-clash-service"
ET.indent(tree, space="  ")
tree.write(path, encoding="utf-8", xml_declaration=True)
PY
expect_failure_preserves "$module_fixture" 'root POM module already registered' \
  --name component-clash --port 8214 --schema component_clash_db

catalog_fixture="$test_root/duplicate-catalog"
make_fixture "$catalog_fixture"
python3 - "$catalog_fixture/architecture/service-catalog.yaml" <<'PY'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
catalog = json.loads(path.read_text(encoding="utf-8"))
catalog["services"].append({
    "service": "catalog-clash-service",
    "owner": "platform-team",
    "schema": "catalog_clash_db",
    "port": 61000,
    "publicApi": [],
    "internalApi": [],
    "eventsPublished": [],
    "eventsConsumed": [],
    "syncDependencies": [],
})
path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
PY
expect_failure_preserves "$catalog_fixture" 'service already registered in catalog' \
  --name catalog-clash --port 61001 --schema another_catalog_db

broken_fixture="$test_root/broken-template"
make_fixture "$broken_fixture"
rm "$broken_fixture/service-template/template-domain/src/main/java/com/xzwl/cloud/template/domain/capability/Capability.java"
expect_failure_preserves "$broken_fixture" 'template is missing required file' \
  --name broken --port 8217 --schema broken_db

rollback_fixture="$test_root/rollback-after-pom"
make_fixture "$rollback_fixture"
rollback_before="$(snapshot_state "$rollback_fixture")"
set +e
rollback_output="$(cd "$rollback_fixture" && \
  XZWL_CREATE_SERVICE_TEST_FAULTS='after-catalog-replace' \
  bash scripts/create-service.sh --name rollback --port 61002 --schema rollback_db 2>&1)"
rollback_exit=$?
set -e
[[ "$rollback_exit" -ne 0 \
  && "$rollback_output" == *'injected failure after catalog replace'* \
  && "$rollback_output" == *'rollback completed'* ]] || {
  printf 'expected injected commit failure, raw=%s output=%s\n' \
    "$rollback_exit" "$rollback_output" >&2
  exit 1
}
[[ "$rollback_before" == "$(snapshot_state "$rollback_fixture")" ]] || {
  printf 'partial commit failure was not rolled back\n' >&2
  exit 1
}
assert_no_transaction_artifacts "$rollback_fixture"

user_fixture="$test_root/fixed-user-command"
make_fixture "$user_fixture"
(cd "$user_fixture" && bash scripts/create-service.sh --name fixed-user --port 61003 --schema fixed_user_db)
assert_generated_service "$user_fixture" fixed-user 61003 fixed_user_db

valid_boundary_fixture="$test_root/valid-name-and-port-boundaries"
make_fixture "$valid_boundary_fixture"
(cd "$valid_boundary_fixture" && bash scripts/create-service.sh \
  --name order-processing --port 1024 --schema order_processing_db)
assert_generated_service "$valid_boundary_fixture" order-processing 1024 order_processing_db
(cd "$valid_boundary_fixture" && bash scripts/create-service.sh \
  --name high-port --port 65535 --schema high_port_db)
assert_generated_service "$valid_boundary_fixture" high-port 65535 high_port_db

(cd "$main_fixture" && bash scripts/create-service.sh \
  --name sample --port 61004 --schema sample_db)
assert_generated_service "$main_fixture" sample 61004 sample_db
assert_no_transaction_artifacts "$main_fixture"

sample_before="$(snapshot_state "$main_fixture")"
expect_failure_preserves "$main_fixture" 'target service already exists' \
  --name sample --port 61004 --schema sample_db
[[ "$sample_before" == "$(snapshot_state "$main_fixture")" ]]

expect_failure_preserves "$main_fixture" 'schema already registered: sample_db' \
  --name sample-copy --port 61005 --schema sample_db

maven_output="$test_root/generated-maven-output.log"
(cd "$main_fixture" && ./mvnw -pl services/sample-service -am test) | tee "$maven_output"
(cd "$main_fixture" && bash scripts/verify-version-drift.sh)
for module in sample-api sample-domain sample-application sample-infrastructure sample-interfaces sample-bootstrap; do
  grep -F "Building $module " "$maven_output" >/dev/null || {
    printf 'generated Maven reactor did not build %s\n' "$module" >&2
    exit 1
  }
done
test -f "$main_fixture/services/sample-service/sample-bootstrap/target/classes/com/xzwl/cloud/sample/TemplateApplication.class"

race_fixture="$test_root/concurrent-generation"
make_fixture "$race_fixture"
for race_index in 1 2 3 4 5 6 7 8; do
  race_name="race${race_index}"
  race_port=$((61010 + race_index))
  set +e
  (cd "$race_fixture" && bash scripts/create-service.sh \
    --name "$race_name" --port "$race_port" --schema "${race_name}_db") \
    >"$test_root/${race_name}-one.log" 2>&1 &
  race_one=$!
  (cd "$race_fixture" && bash scripts/create-service.sh \
    --name "$race_name" --port "$race_port" --schema "${race_name}_db") \
    >"$test_root/${race_name}-two.log" 2>&1 &
  race_two=$!
  wait "$race_one"
  race_one_exit=$?
  wait "$race_two"
  race_two_exit=$?
  set -e
  if ! { [[ "$race_one_exit" -eq 0 && "$race_two_exit" -ne 0 ]] \
    || [[ "$race_one_exit" -ne 0 && "$race_two_exit" -eq 0 ]]; }; then
    printf 'concurrent generation %s must produce one success and one failure: %s/%s\n' \
      "$race_index" "$race_one_exit" "$race_two_exit" >&2
    exit 1
  fi
  assert_generated_service "$race_fixture" "$race_name" "$race_port" "${race_name}_db"
done
assert_no_transaction_artifacts "$race_fixture"

lock_fixture="$test_root/symlink-lock"
make_fixture "$lock_fixture"
ln -s "$lock_fixture/pom.xml" "$lock_fixture/.create-service.lock"
set +e
lock_output="$(cd "$lock_fixture" && bash scripts/create-service.sh \
  --name locked --port 8228 --schema locked_db 2>&1)"
lock_exit=$?
set -e
[[ "$lock_exit" -eq 0 ]] || {
  printf 'stable directory lock must ignore an unrelated legacy lock symlink: raw=%s output=%s\n' \
    "$lock_exit" "$lock_output" >&2
  exit 1
}
test -L "$lock_fixture/.create-service.lock"
[[ "$(readlink "$lock_fixture/.create-service.lock")" == "$lock_fixture/pom.xml" ]]
assert_generated_service "$lock_fixture" locked 8228 locked_db
rm "$lock_fixture/.create-service.lock"
assert_no_transaction_artifacts "$lock_fixture"

printf 'service generator tests passed\n'
