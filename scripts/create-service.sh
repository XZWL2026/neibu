#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

die() {
  printf '%s\n' "$1" >&2
  exit 1
}

command -v python3 >/dev/null 2>&1 || die 'python3 is required by create-service.sh'
python3 - <<'PY' \
  || die 'Python 3.9+ with POSIX fcntl support is required by create-service.sh'
import sys

if sys.version_info < (3, 9):
    raise SystemExit(1)
try:
    import fcntl
except Exception:
    raise SystemExit(1)
PY
python3 - <<'PY' \
  || die 'POSIX O_NOFOLLOW support is required by create-service.sh'
import os

if not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit(1)
PY
python3 - <<'PY' \
  || die 'Linux renameat2 or Darwin renameatx_np with POSIX O_DIRECTORY and pthread_sigmask support is required by create-service.sh'
import ctypes
import os
import signal
import sys

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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "$script_dir/.." && pwd -P)"

exec python3 - "$repo_root" "$@" <<'PY'
from __future__ import annotations

from io import BytesIO
from pathlib import Path
import ctypes
import errno
import fcntl
from hashlib import sha256
import json
import os
import re
import secrets
import shutil
import signal
import stat
import sys
import time
from typing import Callable, Optional
import xml.etree.ElementTree as ET

LAYERS = ("api", "domain", "application", "infrastructure", "interfaces", "bootstrap")
MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
JAVA_RESERVED = frozenset({
    "_", "abstract", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "const", "continue", "default", "do", "double", "else",
    "enum", "exports", "extends", "false", "final", "finally", "float", "for",
    "goto", "if", "implements", "import", "instanceof", "int", "interface",
    "long", "module", "native", "new", "non-sealed", "null", "open", "opens", "package",
    "permits", "private", "protected", "provides", "public", "record", "requires",
    "return", "sealed", "short", "static", "strictfp", "super", "switch",
    "synchronized", "this", "throw", "throws", "to", "transient", "transitive",
    "true", "try", "uses", "var", "void", "volatile", "when", "while", "with",
    "yield",
})
REQUIRED_TEMPLATE_FILES = (
    "pom.xml",
    "template-api/pom.xml",
    "template-domain/pom.xml",
    "template-application/pom.xml",
    "template-infrastructure/pom.xml",
    "template-interfaces/pom.xml",
    "template-bootstrap/pom.xml",
    "template-domain/src/main/java/com/xzwl/cloud/template/domain/capability/Capability.java",
    "template-application/src/main/java/com/xzwl/cloud/template/application/capability/GetCapabilityQuery.java",
    "template-interfaces/src/main/java/com/xzwl/cloud/template/interfaces/http/CapabilityController.java",
    "template-bootstrap/src/main/java/com/xzwl/cloud/template/TemplateApplication.java",
    "template-bootstrap/src/main/resources/application.yml",
)
NATIVE_PROBE_PREFIX = ".create-service.native-probe-"
NATIVE_PROBE_PATTERN = re.compile(r"\.create-service\.native-probe-([0-9a-f]{32})")
NATIVE_PROBE_CLAIM_PATTERN = re.compile(
    r"\.create-service\.native-probe-([0-9a-f]{32})\.claim"
)
NATIVE_PROBE_MARKER = ".NATIVE_PROBE"
NATIVE_PROBE_FILES = frozenset({
    NATIVE_PROBE_MARKER,
    "exchange-first",
    "exchange-second",
    "noreplace-occupied-source",
    "noreplace-occupied-destination",
    "noreplace-missing-source",
    "noreplace-missing-destination",
})
CATALOG_FIELDS = {
    "service", "owner", "schema", "port", "publicApi", "internalApi",
    "eventsPublished", "eventsConsumed", "syncDependencies",
}
TRANSACTION_SIGNALS = {signal.SIGHUP, signal.SIGINT, signal.SIGTERM}


class GenerationError(RuntimeError):
    pass


class ControlledInterruption(GenerationError):
    def __init__(self, signum: int) -> None:
        self.signum = signum
        super().__init__(f"service generation interrupted by signal {signum}")


class CleanupConflict(GenerationError):
    pass


def fail(message: str) -> None:
    raise GenerationError(message)


def ignore_transaction_signals() -> None:
    for signum in TRANSACTION_SIGNALS:
        signal.signal(signum, signal.SIG_IGN)


def parse_arguments(arguments: list[str]) -> tuple[str, int, str]:
    values: dict[str, str] = {}
    names = {"--name": "name", "--port": "port", "--schema": "schema"}
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        index += 1
        if argument not in names:
            fail(f"unknown argument: {argument}")
        key = names[argument]
        if key in values:
            fail(f"duplicate argument: {argument}")
        if index >= len(arguments):
            fail(f"missing value for {argument}")
        values[key] = arguments[index]
        index += 1
    for argument, key in names.items():
        if key not in values:
            fail(f"missing required argument {argument}")

    name = values["name"]
    port_text = values["port"]
    schema = values["schema"]
    if re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*", name) is None:
        fail("invalid service name: use lowercase kebab-case segments")
    reserved = [segment for segment in name.split("-") if segment in JAVA_RESERVED]
    if reserved:
        fail("invalid service name: Java package segment is reserved: " + ", ".join(reserved))
    if re.fullmatch(r"[0-9]+", port_text) is None:
        fail("invalid service port: use decimal 1024..65535")
    port = int(port_text, 10)
    if not 1024 <= port <= 65535:
        fail("invalid service port: use decimal 1024..65535")
    if re.fullmatch(r"[a-z][a-z0-9_]*_db", schema) is None:
        fail("invalid service schema: use a lowercase SQL identifier ending in _db")
    return name, port, schema


def reject_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            fail(f"service catalog contains duplicate key: {key}")
        result[key] = value
    return result


def ensure_regular(path: Path, label: str) -> None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        fail(f"{label} must be a regular file")
    if not stat.S_ISREG(info.st_mode):
        fail(f"{label} must be a regular file")


def ensure_directory(path: Path, label: str) -> None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        fail(f"{label} must be a real directory")
    if not stat.S_ISDIR(info.st_mode):
        fail(f"{label} must be a real directory")


def write_new(path: Path, content: bytes, mode: int = 0o600) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(descriptor, "wb", closefd=False) as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(descriptor)


def write_new_at(directory_fd: int, name: str, content: bytes, mode: int = 0o600) -> None:
    descriptor = os.open(
        name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
        mode, dir_fd=directory_fd,
    )
    try:
        with os.fdopen(descriptor, "wb", closefd=False) as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(descriptor)


def read_bytes_at(directory_fd: int, name: str) -> bytes:
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
    try:
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            return stream.read()
    finally:
        os.close(descriptor)


def same_inode(first: os.stat_result, second: os.stat_result) -> bool:
    return (first.st_dev, first.st_ino) == (second.st_dev, second.st_ino)


def content_hash(content: bytes) -> str:
    return sha256(content).hexdigest()


def fsync_directory(path: Path) -> None:
    flags = os.O_RDONLY | os.O_NOFOLLOW
    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY
    descriptor = os.open(path, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def fsync_tree(root: Path) -> None:
    directories = [root]
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            fail(f"staged service must not contain symbolic links: {path.relative_to(root)}")
        if path.is_dir():
            directories.append(path)
        elif path.is_file():
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
    for directory in reversed(directories):
        fsync_directory(directory)


def remove_directory_contents_by_fd(directory_fd: int) -> None:
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY
    for name in os.listdir(directory_fd):
        info = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        if stat.S_ISDIR(info.st_mode):
            child_fd = os.open(name, flags, dir_fd=directory_fd)
            try:
                opened = os.fstat(child_fd)
                visible = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                if not same_inode(opened, visible):
                    raise OSError(f"probe child directory ownership changed: {name}")
                remove_directory_contents_by_fd(child_fd)
                visible = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                if not same_inode(opened, visible):
                    raise OSError(f"probe child directory ownership changed: {name}")
                os.rmdir(name, dir_fd=directory_fd)
            finally:
                os.close(child_fd)
        else:
            os.unlink(name, dir_fd=directory_fd)
    os.fsync(directory_fd)


class NativeFilesystem:
    LINUX_NOREPLACE = 1
    LINUX_EXCHANGE = 2
    DARWIN_SWAP = 0x00000002
    DARWIN_EXCL = 0x00000004
    def __init__(self) -> None:
        self.libc = ctypes.CDLL(None, use_errno=True)
        if sys.platform.startswith("linux"):
            if not hasattr(self.libc, "renameat2"):
                fail("Linux renameat2 support is required by create-service.sh")
            self.platform = "linux"
            self.renameat2 = self.libc.renameat2
            self.renameat2.argtypes = (
                ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint,
            )
            self.renameat2.restype = ctypes.c_int
        elif sys.platform == "darwin":
            if not hasattr(self.libc, "renameatx_np"):
                fail("Darwin renameatx_np support is required by create-service.sh")
            self.platform = "darwin"
            self.renameatx_np = self.libc.renameatx_np
            self.renameatx_np.argtypes = (
                ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint,
            )
            self.renameatx_np.restype = ctypes.c_int
        else:
            fail("Linux renameat2 or Darwin renameatx_np support is required by create-service.sh")

    @staticmethod
    def encoded_name(name: str) -> bytes:
        if (
            not isinstance(name, str)
            or name in ("", ".", "..")
            or "/" in name
            or (os.altsep is not None and os.altsep in name)
            or Path(name).name != name
        ):
            fail(f"unsafe native filesystem basename: {name!r}")
        return os.fsencode(name)

    def _raise(self, operation: str, source: str, destination: str) -> None:
        number = ctypes.get_errno()
        raise OSError(number, f"{operation} failed for {source} -> {destination}: {os.strerror(number)}")

    def exchange_at(
        self,
        source_fd: int,
        source_name: str,
        destination_fd: int,
        destination_name: str,
    ) -> None:
        encoded_source = self.encoded_name(source_name)
        encoded_destination = self.encoded_name(destination_name)
        if self.platform == "linux":
            result = self.renameat2(
                source_fd, encoded_source, destination_fd, encoded_destination,
                self.LINUX_EXCHANGE,
            )
        else:
            result = self.renameatx_np(
                source_fd, encoded_source, destination_fd, encoded_destination,
                self.DARWIN_SWAP,
            )
        if result != 0:
            self._raise("atomic exchange", source_name, destination_name)

    def no_replace_at(
        self,
        source_fd: int,
        source_name: str,
        destination_fd: int,
        destination_name: str,
    ) -> None:
        encoded_source = self.encoded_name(source_name)
        encoded_destination = self.encoded_name(destination_name)
        if self.platform == "linux":
            result = self.renameat2(
                source_fd, encoded_source, destination_fd, encoded_destination,
                self.LINUX_NOREPLACE,
            )
        else:
            result = self.renameatx_np(
                source_fd, encoded_source, destination_fd, encoded_destination,
                self.DARWIN_EXCL,
            )
        if result != 0:
            self._raise("atomic no-replace rename", source_name, destination_name)


class TestControls:
    def __init__(self) -> None:
        enabled = os.environ.get("XZWL_CREATE_SERVICE_TEST_MODE") == "1"
        self.hook_dir = os.environ.get("XZWL_CREATE_SERVICE_TEST_HOOK_DIR", "") if enabled else ""
        self.participant = (
            os.environ.get("XZWL_CREATE_SERVICE_TEST_PARTICIPANT", "") if enabled else ""
        )
        self.hook_points = {
            value for value in os.environ.get("XZWL_CREATE_SERVICE_TEST_HOOK_POINTS", "").split(",")
            if value
        } if enabled else set()
        self.faults = {
            value for value in os.environ.get("XZWL_CREATE_SERVICE_TEST_FAULTS", "").split(",")
            if value
        } if enabled else set()
        self.restore_attempts: dict[str, int] = {}

    def hook(self, point: str) -> None:
        if not self.hook_dir or point not in self.hook_points:
            return
        directory = Path(self.hook_dir)
        directory.mkdir(parents=True, exist_ok=True)
        suffix = f".{self.participant}" if self.participant else ""
        (directory / f"{point}{suffix}.ready").touch(exist_ok=True)
        continuation = directory / f"{point}.continue"
        deadline = time.monotonic() + 15
        while not continuation.exists():
            if time.monotonic() >= deadline:
                fail(f"test hook timed out: {point}")
            time.sleep(0.01)

    def fail_after_catalog_replace(self) -> None:
        if "after-catalog-replace" in self.faults:
            fail("injected failure after catalog replace")

    def fsync_target_parent(self, descriptor: int) -> None:
        if "target-parent-fsync-failure" in self.faults:
            raise OSError(errno.EIO, "injected target parent directory fsync failure")
        os.fsync(descriptor)

    def before_native_probe_cleanup(self) -> None:
        if "native-probe-cleanup-failure" in self.faults:
            raise OSError("injected native filesystem probe cleanup failure")

    def crash(self, point: str) -> None:
        if f"crash-{point}" in self.faults:
            os._exit(97)

    def before_restore(self, label: str) -> None:
        attempt = self.restore_attempts.get(label, 0) + 1
        self.restore_attempts[label] = attempt
        if label == "catalog" and "catalog-restore-always" in self.faults:
            raise OSError("injected persistent catalog restore failure")
        if label == "catalog" and "catalog-restore-once" in self.faults and attempt == 1:
            raise OSError("injected first catalog restore failure")


def render_service(template: Path, destination: Path, name: str, port: int) -> None:
    for path in template.rglob("*"):
        if path.is_symlink():
            fail(f"template must not contain symbolic links: {path.relative_to(template)}")
    shutil.copytree(template, destination, ignore=shutil.ignore_patterns("target"))
    for layer in LAYERS:
        source = destination / f"template-{layer}"
        target = destination / f"{name}-{layer}"
        if not source.is_dir():
            fail(f"template module is missing: template-{layer}")
        source.rename(target)

    base_package = "com.xzwl.cloud." + name.replace("-", ".")
    package_parts = tuple(base_package.split("."))
    for layer in LAYERS:
        module = destination / f"{name}-{layer}"
        for source_kind in ("main", "test"):
            source_package = module / "src" / source_kind / "java/com/xzwl/cloud/template"
            if source_package.is_dir():
                target_package = module / "src" / source_kind / "java" / Path(*package_parts)
                target_package.parent.mkdir(parents=True, exist_ok=True)
                source_package.rename(target_package)

    replacements = (
        ("service-template", f"{name}-service"),
        ("template-", f"{name}-"),
        ("com.xzwl.cloud.template", base_package),
        ("__SERVICE_PORT__", str(port)),
    )
    for path in sorted(destination.rglob("*")):
        if path.is_file():
            text = path.read_text(encoding="utf-8")
            for old, new in replacements:
                text = text.replace(old, new)
            path.write_text(text, encoding="utf-8")

    ET.register_namespace("", MAVEN_NAMESPACE)
    namespace = {"m": MAVEN_NAMESPACE}
    for pom in sorted(destination.rglob("pom.xml")):
        tree = ET.parse(pom)
        relative_path = tree.getroot().find("m:parent/m:relativePath", namespace)
        if relative_path is None:
            fail(f"generated POM has no parent relativePath: {pom}")
        relative_path.text = "../.." if pom == destination / "pom.xml" else "../../.."
        ET.indent(tree, space="  ")
        tree.write(pom, encoding="utf-8", xml_declaration=True)

    package_path = Path(*package_parts)
    required = (
        destination / f"{name}-domain/src/main/java" / package_path / "domain/capability/Capability.java",
        destination / f"{name}-application/src/main/java" / package_path / "application/capability/GetCapabilityQuery.java",
        destination / f"{name}-interfaces/src/main/java" / package_path / "interfaces/http/CapabilityController.java",
        destination / f"{name}-bootstrap/src/main/java" / package_path / "TemplateApplication.java",
        destination / f"{name}-bootstrap/src/main/resources/application.yml",
    )
    for path in required:
        if not path.is_file():
            fail(f"required rendered file is missing: {path.relative_to(destination)}")
    for path in sorted(destination.rglob("*")):
        if path.is_file():
            text = path.read_text(encoding="utf-8")
            if any(token in text for token in (
                "service-template", "template-", "com.xzwl.cloud.template", "__SERVICE_PORT__"
            )):
                fail(f"unresolved template token in {path.relative_to(destination)}")


def build_metadata(
    pom_snapshot: bytes,
    catalog_snapshot: bytes,
    next_pom: Path,
    next_catalog: Path,
    name: str,
    port: int,
    schema: str,
) -> None:
    service_name = f"{name}-service"
    module_name = f"services/{service_name}"
    ET.register_namespace("", MAVEN_NAMESPACE)
    try:
        parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
        tree = ET.parse(BytesIO(pom_snapshot), parser=parser)
    except ET.ParseError as error:
        fail(f"root pom.xml is invalid XML: {error}")
    project = tree.getroot()
    if project.tag != f"{{{MAVEN_NAMESPACE}}}project":
        fail("root pom.xml must use the canonical Maven namespace")
    namespace = {"m": MAVEN_NAMESPACE}
    module_blocks = project.findall("m:modules", namespace)
    if len(module_blocks) != 1:
        fail("root POM must contain exactly one modules block")
    modules = [(item.text or "").strip() for item in module_blocks[0].findall("m:module", namespace)]
    if module_name in modules:
        fail(f"root POM module already registered: {module_name}")
    if modules.count("service-template") != 1:
        fail("root POM must aggregate service-template exactly once")
    ET.SubElement(module_blocks[0], f"{{{MAVEN_NAMESPACE}}}module").text = module_name
    ET.indent(tree, space="  ")
    tree.write(next_pom, encoding="utf-8", xml_declaration=True)
    ET.parse(next_pom)

    try:
        catalog = json.loads(catalog_snapshot.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"service catalog is invalid JSON-compatible YAML: {error}")
    if (
        not isinstance(catalog, dict)
        or set(catalog) != {"version", "services"}
        or type(catalog["version"]) is not int
        or catalog["version"] != 1
        or not isinstance(catalog["services"], list)
    ):
        fail("service catalog must contain version 1 and a services array")
    for entry in catalog["services"]:
        if not isinstance(entry, dict) or set(entry) != CATALOG_FIELDS:
            fail("service catalog entry fields are invalid")
        if not isinstance(entry["service"], str) or not entry["service"]:
            fail("service catalog service must be a non-empty string")
        if not isinstance(entry["owner"], str) or not entry["owner"]:
            fail("service catalog owner must be a non-empty string")
        if (
            not isinstance(entry["schema"], str)
            or not entry["schema"]
            or (
                entry["schema"] != "none"
                and re.fullmatch(r"[a-z][a-z0-9_]*", entry["schema"]) is None
            )
        ):
            fail("service catalog schema must be a safe lowercase identifier or none")
        if type(entry["port"]) is not int or not 1024 <= entry["port"] <= 65535:
            fail("service catalog port must be an integer in 1024..65535")
        for field in (
            "publicApi", "internalApi", "eventsPublished", "eventsConsumed", "syncDependencies",
        ):
            values = entry[field]
            if (
                not isinstance(values, list)
                or any(not isinstance(value, str) or not value for value in values)
                or len(values) != len(set(values))
            ):
                fail(f"service catalog {field} must be a unique non-empty string array")
    services = [entry["service"] for entry in catalog["services"]]
    ports = [entry["port"] for entry in catalog["services"]]
    schemas = [entry["schema"] for entry in catalog["services"]]
    governed_schemas = [value for value in schemas if value != "none"]
    if (
        len(services) != len(set(services))
        or len(ports) != len(set(ports))
        or len(governed_schemas) != len(set(governed_schemas))
    ):
        fail("service catalog already contains duplicate services, ports, or schemas")
    if service_name in services:
        fail(f"service already registered in catalog: {service_name}")
    if port in ports:
        fail(f"port already registered: {port}")
    if schema in schemas:
        fail(f"schema already registered: {schema}")
    catalog["services"].append({
        "service": service_name,
        "owner": "platform-team",
        "schema": schema,
        "port": port,
        "publicApi": [],
        "internalApi": [],
        "eventsPublished": [],
        "eventsConsumed": [],
        "syncDependencies": [],
    })
    next_catalog.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    json.loads(next_catalog.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)


class Transaction:
    def __init__(self, repo_root: Path, name: str, port: int, schema: str) -> None:
        self.repo_root = repo_root
        self.name = name
        self.port = port
        self.schema = schema
        self.template = repo_root / "service-template"
        self.target_relative = Path("services") / f"{name}-service"
        self.target = repo_root / self.target_relative
        self.root_pom = repo_root / "pom.xml"
        self.catalog = repo_root / "architecture/service-catalog.yaml"
        self.native = NativeFilesystem()
        self.lock_fd: Optional[int] = None
        self.lock_stat: Optional[os.stat_result] = None
        self.lock_acquired = False
        self.stable_holding_parent_fd: Optional[int] = None
        self.stable_holding_fd: Optional[int] = None
        self.stable_holding_stat: Optional[os.stat_result] = None
        self.stable_holding_name = ""
        self.stable_holding_root = repo_root.parent
        self.architecture_fd: Optional[int] = None
        self.services_fd: Optional[int] = None
        self.transaction_dir: Optional[Path] = None
        self.transaction_name: Optional[str] = None
        self.transaction_fd: Optional[int] = None
        self.transaction_stat: Optional[os.stat_result] = None
        self.journal_data: dict[str, object] = {}
        self.pom_snapshot = b""
        self.catalog_snapshot = b""
        self.pom_mode = 0o644
        self.catalog_mode = 0o644
        self.target_visible = False
        self.published_stat: Optional[os.stat_result] = None
        self.services_stat: Optional[os.stat_result] = None
        self.committed = False
        self.recovered_matching_commit = False
        self.post_commit_notice = ""
        self.owner_token = secrets.token_hex(32)
        self.pom_replaced = False
        self.catalog_replaced = False
        self.controls = TestControls()
        self.rollback_completed = False
        self.rollback_notes: list[str] = []
        self.recovery_errors: list[str] = []

    def acquire_lock(self) -> None:
        self.controls.hook("before-lock-open")
        flags = os.O_RDONLY | os.O_NOFOLLOW
        if not hasattr(os, "O_DIRECTORY"):
            fail("POSIX O_DIRECTORY support is required by create-service.sh")
        flags |= os.O_DIRECTORY
        try:
            self.lock_fd = os.open(self.repo_root, flags)
        except OSError as error:
            if error.errno in (errno.ELOOP, errno.EMLINK):
                fail("repository root lock must not follow a symbolic link")
            raise
        opened = os.fstat(self.lock_fd)
        try:
            visible = os.lstat(self.repo_root)
        except FileNotFoundError:
            fail("repository root changed while opening the global lock")
        if not stat.S_ISDIR(opened.st_mode) or not same_inode(opened, visible):
            fail("repository root changed while opening the global lock")
        self.lock_stat = opened
        self.controls.hook("lock-verified-before-flock")
        try:
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            fail("another service generation is in progress")
        try:
            locked_visible = os.lstat(self.repo_root)
        except FileNotFoundError:
            fail("repository root changed while acquiring the global lock")
        if not same_inode(opened, locked_visible):
            fail("repository root changed while acquiring the global lock")
        self.lock_acquired = True
        self.controls.hook("after-lock")

    def open_bound_repo_directory(self, name: str, label: str) -> tuple[int, os.stat_result]:
        if self.lock_fd is None or self.lock_stat is None:
            fail(f"{label} cannot be bound before the repository lock")
        descriptor = os.open(
            name, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY, dir_fd=self.lock_fd,
        )
        try:
            opened = os.fstat(descriptor)
            visible = os.stat(name, dir_fd=self.lock_fd, follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened.st_mode)
                or not same_inode(opened, visible)
                or opened.st_dev != self.lock_stat.st_dev
            ):
                fail(f"{label} changed while binding its directory descriptor")
            return descriptor, opened
        except Exception:
            os.close(descriptor)
            raise

    def prepare_stable_holding_root(self) -> None:
        if self.lock_stat is None:
            fail("stable holding root cannot be prepared before the repository lock")
        identity = (
            os.fsencode(str(self.repo_root))
            + b"\0"
            + f"{self.lock_stat.st_dev}:{self.lock_stat.st_ino}".encode("ascii")
        )
        self.stable_holding_name = (
            ".xzwl-create-service-holding-" + sha256(identity).hexdigest()[:32]
        )
        self.stable_holding_root = self.repo_root.parent / self.stable_holding_name
        flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY
        parent = self.repo_root.parent
        try:
            parent_fd = os.open(parent, flags)
        except OSError as error:
            fail(f"stable holding root parent cannot be opened safely: {error}")
        root_fd: Optional[int] = None
        try:
            parent_opened = os.fstat(parent_fd)
            parent_visible = os.lstat(parent)
            if (
                not stat.S_ISDIR(parent_opened.st_mode)
                or not same_inode(parent_opened, parent_visible)
            ):
                fail("stable holding root parent changed while binding its descriptor")
            if (
                parent_opened.st_dev != self.lock_stat.st_dev
                or "stable-root-cross-filesystem" in self.controls.faults
            ):
                fail("stable holding root parent must be on the repository filesystem")
            if "stable-root-parent-unwritable" in self.controls.faults:
                fail("stable holding root parent is not writable")

            try:
                visible = os.stat(
                    self.stable_holding_name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
            except FileNotFoundError:
                try:
                    os.mkdir(self.stable_holding_name, 0o700, dir_fd=parent_fd)
                except OSError as error:
                    fail(f"stable holding root cannot be created: {error}")
                os.fsync(parent_fd)
                visible = os.stat(
                    self.stable_holding_name,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )

            try:
                root_fd = os.open(
                    self.stable_holding_name,
                    flags,
                    dir_fd=parent_fd,
                )
            except OSError as error:
                fail(f"stable holding root must be a no-follow directory: {error}")
            opened = os.fstat(root_fd)
            visible = os.stat(
                self.stable_holding_name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            actual_mode = stat.S_IMODE(opened.st_mode)
            owner_matches = opened.st_uid == os.geteuid()
            device_matches = opened.st_dev == self.lock_stat.st_dev
            if "stable-root-owner" in self.controls.faults:
                owner_matches = False
            if "stable-root-device" in self.controls.faults:
                device_matches = False
            if (
                not stat.S_ISDIR(opened.st_mode)
                or not same_inode(opened, visible)
                or actual_mode != 0o700
                or not owner_matches
                or not device_matches
            ):
                fail(
                    "stable holding root must be an owned mode-0700 real directory "
                    "on the repository filesystem"
                )
            self.stable_holding_parent_fd = parent_fd
            self.stable_holding_fd = root_fd
            self.stable_holding_stat = opened
            parent_fd = -1
            root_fd = None
        finally:
            if root_fd is not None:
                os.close(root_fd)
            if parent_fd >= 0:
                os.close(parent_fd)

        self.cleanup_stale_native_probes()

    @staticmethod
    def native_probe_evidence(token: str) -> bytes:
        return f"xzwl-create-service-native-probe-v1:{token}\n".encode("ascii")

    def validate_native_probe_claim(
        self, claim_name: str, token: str,
    ) -> os.stat_result:
        if self.stable_holding_fd is None or self.stable_holding_stat is None:
            fail("stable holding root is unavailable for native probe evidence")
        claim_info = os.stat(
            claim_name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
        )
        if (
            not stat.S_ISREG(claim_info.st_mode)
            or stat.S_IMODE(claim_info.st_mode) != 0o600
            or claim_info.st_uid != os.geteuid()
            or claim_info.st_dev != self.stable_holding_stat.st_dev
            or read_bytes_at(self.stable_holding_fd, claim_name)
            != self.native_probe_evidence(token)
        ):
            fail(
                f"native filesystem probe claim is invalid and was retained: "
                f"{self.stable_holding_root / claim_name}"
            )
        return claim_info

    def validate_native_probe_directory(
        self, probe_name: str,
    ) -> tuple[int, os.stat_result]:
        if self.stable_holding_fd is None or self.stable_holding_stat is None:
            fail("stable holding root is unavailable for native probe recovery")
        visible = os.stat(
            probe_name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
        )
        if (
            not stat.S_ISDIR(visible.st_mode)
            or stat.S_IMODE(visible.st_mode) != 0o700
            or visible.st_uid != os.geteuid()
            or visible.st_dev != self.stable_holding_stat.st_dev
        ):
            fail(
                f"native filesystem probe directory is invalid and was retained: "
                f"{self.stable_holding_root / probe_name}"
            )
        descriptor = os.open(
            probe_name,
            os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
            dir_fd=self.stable_holding_fd,
        )
        try:
            opened = os.fstat(descriptor)
            current = os.stat(
                probe_name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
            )
            if not same_inode(opened, visible) or not same_inode(opened, current):
                fail(
                    f"native filesystem probe directory changed while binding: "
                    f"{self.stable_holding_root / probe_name}"
                )
            return descriptor, opened
        except Exception:
            os.close(descriptor)
            raise

    def remove_native_probe(
        self,
        probe_name: str,
        claim_name: str,
        token: str,
        probe_fd: int,
        probe_info: os.stat_result,
        claim_info: os.stat_result,
        *,
        current_probe: bool,
        native_claim_available: bool = True,
        allow_markerless_bound_cleanup: bool = False,
    ) -> None:
        if self.stable_holding_fd is None:
            fail("stable holding root descriptor unavailable for native probe cleanup")
        current = os.stat(
            probe_name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
        )
        if (
            not same_inode(probe_info, os.fstat(probe_fd))
            or not same_inode(probe_info, current)
        ):
            raise OSError("native filesystem probe path ownership changed")
        if current_probe:
            self.controls.hook("before-native-probe-fd-cleanup")
            self.controls.before_native_probe_cleanup()

        entries = set(os.listdir(probe_fd))
        unknown = entries - NATIVE_PROBE_FILES
        if unknown:
            raise OSError(
                "native filesystem probe contains unknown entries: "
                + ", ".join(sorted(unknown))
            )
        marker_present = NATIVE_PROBE_MARKER in entries
        if not marker_present:
            if not allow_markerless_bound_cleanup:
                raise OSError(
                    "native filesystem probe has no matching ownership marker; "
                    "the directory was retained"
                )
            if entries:
                raise OSError("uninitialized native filesystem probe contains data")
        identities: dict[str, os.stat_result] = {}
        for name in sorted(entries):
            info = os.stat(name, dir_fd=probe_fd, follow_symlinks=False)
            if not stat.S_ISREG(info.st_mode):
                raise OSError(f"native filesystem probe entry is not a regular file: {name}")
            identities[name] = info
        if (
            marker_present
            and read_bytes_at(probe_fd, NATIVE_PROBE_MARKER)
            != self.native_probe_evidence(token)
        ):
            raise OSError("native filesystem probe marker does not match its claim")

        if not native_claim_available:
            # The capability under test cannot claim a pathname atomically.  This
            # branch is limited to disposing of the random directory that this
            # process has already mkdir/open/inode-bound; every public entry is
            # rechecked and a concurrent replacement therefore fails closed.
            for entry in sorted(entries - {NATIVE_PROBE_MARKER}):
                visible = os.stat(entry, dir_fd=probe_fd, follow_symlinks=False)
                if not same_inode(identities[entry], visible):
                    raise CleanupConflict(
                        f"native filesystem probe entry changed before fallback cleanup: {entry}"
                    )
                os.unlink(entry, dir_fd=probe_fd)
            os.fsync(probe_fd)
            if marker_present:
                visible = os.stat(
                    NATIVE_PROBE_MARKER, dir_fd=probe_fd, follow_symlinks=False,
                )
                if not same_inode(identities[NATIVE_PROBE_MARKER], visible):
                    raise CleanupConflict(
                        "native filesystem probe marker changed before fallback cleanup"
                    )
                os.unlink(NATIVE_PROBE_MARKER, dir_fd=probe_fd)
                os.fsync(probe_fd)
            if os.listdir(probe_fd):
                raise CleanupConflict(
                    "native filesystem probe gained entries during fallback cleanup"
                )
            visible_probe = os.stat(
                probe_name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
            )
            if not same_inode(probe_info, visible_probe):
                raise CleanupConflict(
                    "native filesystem probe path changed before fallback removal"
                )
            os.rmdir(probe_name, dir_fd=self.stable_holding_fd)
            os.fsync(self.stable_holding_fd)
            visible_claim = os.stat(
                claim_name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
            )
            if not same_inode(claim_info, visible_claim):
                raise CleanupConflict(
                    "native filesystem probe claim changed before fallback removal"
                )
            os.unlink(claim_name, dir_fd=self.stable_holding_fd)
            os.fsync(self.stable_holding_fd)
            return

        def clean_claimed_probe(claimed_fd: int) -> None:
            claimed_entries = set(os.listdir(claimed_fd))
            if claimed_entries != entries:
                raise CleanupConflict(
                    "native filesystem probe entries changed while claiming its directory"
                )
            claimed_identities = {
                entry: os.stat(entry, dir_fd=claimed_fd, follow_symlinks=False)
                for entry in claimed_entries
            }
            for entry, expected in claimed_identities.items():
                if not same_inode(identities[entry], expected):
                    raise CleanupConflict(
                        f"native filesystem probe entry changed while claiming: {entry}"
                    )
            ordered_entries = sorted(claimed_entries - {NATIVE_PROBE_MARKER})
            if marker_present:
                ordered_entries.append(NATIVE_PROBE_MARKER)
            for entry in ordered_entries:
                self.remove_cleanup_entry(
                    claimed_fd, entry, claimed_identities[entry],
                )
                os.fsync(claimed_fd)
            if os.listdir(claimed_fd):
                raise CleanupConflict(
                    "native filesystem probe gained entries during cleanup"
                )

        self.remove_cleanup_entry(
            self.stable_holding_fd,
            probe_name,
            probe_info,
            directory_cleaner=clean_claimed_probe,
        )
        os.fsync(self.stable_holding_fd)
        self.remove_cleanup_entry(
            self.stable_holding_fd, claim_name, claim_info,
        )
        os.fsync(self.stable_holding_fd)

    def cleanup_stale_native_probes(self) -> None:
        if self.stable_holding_fd is None:
            fail("stable holding root descriptor unavailable for native probe recovery")
        names = set(os.listdir(self.stable_holding_fd))
        probe_names: dict[str, str] = {}
        claim_names: dict[str, str] = {}
        for name in sorted(names):
            probe_match = NATIVE_PROBE_PATTERN.fullmatch(name)
            claim_match = NATIVE_PROBE_CLAIM_PATTERN.fullmatch(name)
            if probe_match is not None:
                probe_names[probe_match.group(1)] = name
            elif claim_match is not None:
                claim_names[claim_match.group(1)] = name
            elif name.startswith(".create-service.cleanup-"):
                continue
            else:
                fail(f"unexpected stable holding artifact: {self.stable_holding_root / name}")

        for token in probe_names:
            if token not in claim_names:
                fail(
                    f"unclaimed native filesystem probe was retained: "
                    f"{self.stable_holding_root / probe_names[token]}"
                )
        for token, claim_name in sorted(claim_names.items()):
            claim_info = self.validate_native_probe_claim(claim_name, token)
            probe_name = probe_names.get(token)
            if probe_name is None:
                self.remove_cleanup_entry(
                    self.stable_holding_fd, claim_name, claim_info,
                )
                os.fsync(self.stable_holding_fd)
                continue
            probe_fd, probe_info = self.validate_native_probe_directory(probe_name)
            try:
                self.remove_native_probe(
                    probe_name,
                    claim_name,
                    token,
                    probe_fd,
                    probe_info,
                    claim_info,
                    current_probe=False,
                )
            finally:
                os.close(probe_fd)

    def create_private_repo_directory(
        self, prefix: str,
    ) -> tuple[str, Path, int, os.stat_result]:
        if self.lock_fd is None:
            fail("repository descriptor unavailable for private directory creation")
        for _attempt in range(128):
            name = prefix + secrets.token_hex(16)
            try:
                os.mkdir(name, 0o700, dir_fd=self.lock_fd)
            except FileExistsError:
                continue
            descriptor: Optional[int] = None
            try:
                descriptor = os.open(
                    name,
                    os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                    dir_fd=self.lock_fd,
                )
                opened = os.fstat(descriptor)
                visible = os.stat(name, dir_fd=self.lock_fd, follow_symlinks=False)
                if not same_inode(opened, visible):
                    raise OSError("private directory ownership changed while opening")
                os.fsync(self.lock_fd)
                return name, self.repo_root / name, descriptor, opened
            except Exception:
                if descriptor is not None:
                    os.close(descriptor)
                try:
                    os.rmdir(name, dir_fd=self.lock_fd)
                    os.fsync(self.lock_fd)
                except OSError:
                    pass
                raise
        fail("unable to allocate a private repository directory")

    def create_private_probe_directory(
        self,
    ) -> tuple[str, str, str, Path, int, os.stat_result, os.stat_result]:
        if self.stable_holding_fd is None:
            fail("stable holding root descriptor unavailable for native probe creation")
        for _attempt in range(128):
            token = secrets.token_hex(16)
            name = NATIVE_PROBE_PREFIX + token
            claim_name = name + ".claim"
            try:
                write_new_at(
                    self.stable_holding_fd,
                    claim_name,
                    self.native_probe_evidence(token),
                )
            except FileExistsError:
                continue
            os.fsync(self.stable_holding_fd)
            claim_info = self.validate_native_probe_claim(claim_name, token)
            descriptor: Optional[int] = None
            opened: Optional[os.stat_result] = None
            directory_bound = False
            try:
                os.mkdir(name, 0o700, dir_fd=self.stable_holding_fd)
                descriptor = os.open(
                    name, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                    dir_fd=self.stable_holding_fd,
                )
                opened = os.fstat(descriptor)
                visible = os.stat(
                    name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
                )
                if not same_inode(opened, visible):
                    raise OSError("private directory ownership changed while opening")
                directory_bound = True
                write_new_at(
                    descriptor,
                    NATIVE_PROBE_MARKER,
                    self.native_probe_evidence(token),
                )
                os.fsync(descriptor)
                os.fsync(self.stable_holding_fd)
                return (
                    token,
                    name,
                    claim_name,
                    self.stable_holding_root / name,
                    descriptor,
                    opened,
                    claim_info,
                )
            except Exception as original_error:
                cleanup_error: Optional[Exception] = None
                try:
                    # A durable claim proves only the claim.  Until mkdir plus
                    # descriptor/inode binding succeeds, the directory path may
                    # belong to another process and must remain untouched.
                    if directory_bound and descriptor is not None and opened is not None:
                        self.remove_native_probe(
                            name,
                            claim_name,
                            token,
                            descriptor,
                            opened,
                            claim_info,
                            current_probe=False,
                            allow_markerless_bound_cleanup=True,
                        )
                except Exception as error:
                    cleanup_error = error
                finally:
                    if descriptor is not None:
                        os.close(descriptor)
                if cleanup_error is not None:
                    raise OSError(
                        f"native filesystem probe creation failed: {original_error}; "
                        f"owned cleanup also failed: {cleanup_error}"
                    ) from original_error
                raise
        fail("unable to allocate a private stable-root native probe directory")

    def probe_native_filesystem(self) -> None:
        probe_token: Optional[str] = None
        probe_name: Optional[str] = None
        claim_name: Optional[str] = None
        probe: Optional[Path] = None
        probe_info: Optional[os.stat_result] = None
        claim_info: Optional[os.stat_result] = None
        probe_fd: Optional[int] = None
        operation_error: Optional[Exception] = None
        cleanup_error: Optional[Exception] = None
        try:
            (
                probe_token,
                probe_name,
                claim_name,
                probe,
                probe_fd,
                probe_info,
                claim_info,
            ) = self.create_private_probe_directory()
            first_bytes = b"xzwl-native-exchange-first\n"
            second_bytes = b"xzwl-native-exchange-second\n"
            write_new_at(probe_fd, "exchange-first", first_bytes)
            write_new_at(probe_fd, "exchange-second", second_bytes)
            first_info = os.stat("exchange-first", dir_fd=probe_fd, follow_symlinks=False)
            second_info = os.stat("exchange-second", dir_fd=probe_fd, follow_symlinks=False)

            self.native.exchange_at(
                probe_fd, "exchange-first", probe_fd, "exchange-second",
            )
            self.controls.hook("after-native-probe-exchange")
            if (
                read_bytes_at(probe_fd, "exchange-first") != second_bytes
                or read_bytes_at(probe_fd, "exchange-second") != first_bytes
                or not same_inode(
                    os.stat("exchange-first", dir_fd=probe_fd, follow_symlinks=False), second_info,
                )
                or not same_inode(
                    os.stat("exchange-second", dir_fd=probe_fd, follow_symlinks=False), first_info,
                )
            ):
                raise OSError("atomic exchange did not swap bytes and inodes")
            self.native.exchange_at(
                probe_fd, "exchange-first", probe_fd, "exchange-second",
            )
            if (
                read_bytes_at(probe_fd, "exchange-first") != first_bytes
                or read_bytes_at(probe_fd, "exchange-second") != second_bytes
                or not same_inode(
                    os.stat("exchange-first", dir_fd=probe_fd, follow_symlinks=False), first_info,
                )
                or not same_inode(
                    os.stat("exchange-second", dir_fd=probe_fd, follow_symlinks=False), second_info,
                )
            ):
                raise OSError("atomic exchange did not restore bytes and inodes")

            source_bytes = b"xzwl-native-noreplace-source\n"
            destination_bytes = b"xzwl-native-noreplace-destination\n"
            write_new_at(probe_fd, "noreplace-occupied-source", source_bytes)
            write_new_at(probe_fd, "noreplace-occupied-destination", destination_bytes)
            source_info = os.stat(
                "noreplace-occupied-source", dir_fd=probe_fd, follow_symlinks=False,
            )
            destination_info = os.stat(
                "noreplace-occupied-destination", dir_fd=probe_fd, follow_symlinks=False,
            )
            try:
                self.native.no_replace_at(
                    probe_fd, "noreplace-occupied-source",
                    probe_fd, "noreplace-occupied-destination",
                )
            except OSError as error:
                if error.errno != errno.EEXIST:
                    raise
            else:
                raise OSError("atomic no-replace overwrote an existing destination")
            if (
                read_bytes_at(probe_fd, "noreplace-occupied-source") != source_bytes
                or read_bytes_at(probe_fd, "noreplace-occupied-destination") != destination_bytes
                or not same_inode(
                    os.stat(
                        "noreplace-occupied-source", dir_fd=probe_fd, follow_symlinks=False,
                    ), source_info,
                )
                or not same_inode(
                    os.stat(
                        "noreplace-occupied-destination", dir_fd=probe_fd,
                        follow_symlinks=False,
                    ), destination_info,
                )
            ):
                raise OSError("failed no-replace changed source or existing destination")

            missing_bytes = b"xzwl-native-noreplace-missing\n"
            write_new_at(probe_fd, "noreplace-missing-source", missing_bytes)
            missing_info = os.stat(
                "noreplace-missing-source", dir_fd=probe_fd, follow_symlinks=False,
            )
            self.native.no_replace_at(
                probe_fd, "noreplace-missing-source", probe_fd, "noreplace-missing-destination",
            )
            if (
                "noreplace-missing-source" in os.listdir(probe_fd)
                or read_bytes_at(probe_fd, "noreplace-missing-destination") != missing_bytes
                or not same_inode(
                    os.stat(
                        "noreplace-missing-destination", dir_fd=probe_fd,
                        follow_symlinks=False,
                    ), missing_info,
                )
            ):
                raise OSError("successful no-replace did not preserve the source inode")
            os.fsync(probe_fd)
        except Exception as error:
            operation_error = error
        finally:
            try:
                if (
                    probe_token is None
                    or probe_info is None
                    or claim_info is None
                    or probe_name is None
                    or claim_name is None
                    or probe is None
                ):
                    raise OSError("native filesystem probe ownership evidence is unavailable")
                if probe_fd is None:
                    if self.stable_holding_fd is None:
                        raise OSError("stable holding root unavailable for probe cleanup")
                    probe_fd = os.open(
                        probe_name, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                        dir_fd=self.stable_holding_fd,
                    )
                if not same_inode(probe_info, os.fstat(probe_fd)):
                    raise OSError("native filesystem probe descriptor ownership changed")
                self.remove_native_probe(
                    probe_name,
                    claim_name,
                    probe_token,
                    probe_fd,
                    probe_info,
                    claim_info,
                    current_probe=True,
                    native_claim_available=not (
                        isinstance(operation_error, OSError)
                        and operation_error.errno
                        in {errno.ENOTSUP, errno.EOPNOTSUPP, errno.EINVAL}
                    ),
                )
            except Exception as error:
                cleanup_error = error
            finally:
                if probe_fd is not None:
                    try:
                        os.close(probe_fd)
                    except OSError as error:
                        if cleanup_error is None:
                            cleanup_error = error

        if cleanup_error is not None:
            cleanup_path = (
                probe if probe is not None
                else self.stable_holding_root / ".create-service.native-probe"
            )
            detail = (
                "; native probe cleanup failed and evidence was retained at "
                f"{cleanup_path}: {cleanup_error}"
            )
            if operation_error is not None:
                fail(f"native filesystem capability probe failed: {operation_error}{detail}")
            fail(f"native filesystem capability probe cleanup failed{detail}")
        if operation_error is not None:
            fail(f"native filesystem capability probe failed: {operation_error}")

    def verify_transaction_filesystem(self) -> None:
        if self.lock_stat is None:
            fail("repository filesystem unavailable before global lock")
        paths = (
            (self.root_pom, "root pom.xml", False),
            (self.catalog, "architecture/service-catalog.yaml", False),
            (self.repo_root / "services", "services directory", True),
            (self.repo_root / "architecture", "architecture directory", True),
            (self.template, "service-template directory", True),
        )
        for path, label, directory in paths:
            if directory:
                ensure_directory(path, label)
            else:
                ensure_regular(path, label)
            if path.lstat().st_dev != self.lock_stat.st_dev:
                fail(f"{label} must be on the repository filesystem")
        self.architecture_fd, _architecture_stat = self.open_bound_repo_directory(
            "architecture", "architecture directory",
        )
        self.services_fd, self.services_stat = self.open_bound_repo_directory(
            "services", "services directory",
        )

    def snapshot(self) -> None:
        ensure_regular(self.root_pom, "root pom.xml")
        ensure_regular(self.catalog, "architecture/service-catalog.yaml")
        ensure_directory(self.repo_root / "services", "services")
        ensure_directory(self.repo_root / "architecture", "architecture")
        ensure_directory(self.template, "service-template")
        if self.services_fd is None:
            fail("services directory descriptor unavailable before snapshot")
        self.services_stat = os.fstat(self.services_fd)
        self.pom_mode = stat.S_IMODE(self.root_pom.lstat().st_mode)
        self.catalog_mode = stat.S_IMODE(self.catalog.lstat().st_mode)
        self.pom_snapshot = self.root_pom.read_bytes()
        self.catalog_snapshot = self.catalog.read_bytes()

    def validate_template(self) -> None:
        for relative in REQUIRED_TEMPLATE_FILES:
            path = self.template / relative
            try:
                info = path.lstat()
            except FileNotFoundError:
                fail(f"template is missing required file: {relative}")
            if not stat.S_ISREG(info.st_mode):
                fail(f"template is missing required file: {relative}")

    def create_staging(self) -> tuple[Path, Path, Path]:
        (
            self.transaction_name,
            self.transaction_dir,
            self.transaction_fd,
            self.transaction_stat,
        ) = self.create_private_repo_directory(".create-service.")
        write_new(self.transaction_dir / "pom.backup", self.pom_snapshot)
        write_new(self.transaction_dir / "catalog.backup", self.catalog_snapshot)
        self.journal_data = {
            "version": 2,
            "ownerToken": self.owner_token,
            "service": f"{self.name}-service",
            "name": self.name,
            "port": self.port,
            "schema": self.schema,
            "target": self.target_relative.as_posix(),
            "stage": "PREPARING",
            "pomOriginalSha256": content_hash(self.pom_snapshot),
            "catalogOriginalSha256": content_hash(self.catalog_snapshot),
            "pomIntendedSha256": None,
            "catalogIntendedSha256": None,
            "stagedDevice": None,
            "stagedInode": None,
            "servicesDevice": self.services_stat.st_dev if self.services_stat else None,
            "servicesInode": self.services_stat.st_ino if self.services_stat else None,
            "recoveryErrors": [],
        }
        self.write_journal_state("PREPARING")
        return (
            self.transaction_dir / f"{self.name}-service",
            self.transaction_dir / "pom.next",
            self.transaction_dir / "catalog.next",
        )

    @property
    def journal_path(self) -> Path:
        if self.transaction_dir is None:
            fail("transaction journal unavailable before staging")
        return self.transaction_dir / "RECOVERY.json"

    def write_journal_state(self, stage: str) -> None:
        if self.transaction_dir is None:
            fail("transaction journal unavailable before staging")
        self.journal_data["stage"] = stage
        temporary = self.transaction_dir / f".RECOVERY.{secrets.token_hex(8)}.tmp"
        payload = (json.dumps(self.journal_data, indent=2, sort_keys=True) + "\n").encode("utf-8")
        write_new(temporary, payload)
        os.replace(temporary, self.journal_path)
        fsync_directory(self.transaction_dir)
        fsync_directory(self.repo_root)

    def prepare_journal(self, staged: Path, next_pom: Path, next_catalog: Path) -> None:
        fsync_tree(staged)
        for path in (next_pom, next_catalog):
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        if self.transaction_dir is not None:
            fsync_directory(self.transaction_dir)
        staged_info = staged.lstat()
        self.published_stat = staged_info
        self.journal_data.update({
            "pomIntendedSha256": content_hash(next_pom.read_bytes()),
            "catalogIntendedSha256": content_hash(next_catalog.read_bytes()),
            "stagedDevice": staged_info.st_dev,
            "stagedInode": staged_info.st_ino,
        })
        self.write_journal_state("PREPARED")

    def verify_metadata(self) -> None:
        ensure_regular(self.root_pom, "root pom.xml")
        ensure_regular(self.catalog, "architecture/service-catalog.yaml")
        if self.root_pom.read_bytes() != self.pom_snapshot:
            fail("root pom.xml changed after transaction snapshot")
        if self.catalog.read_bytes() != self.catalog_snapshot:
            fail("service catalog changed after transaction snapshot")

    def publish_committed_target(self, staged: Path) -> None:
        if self.lock_fd is None or self.services_fd is None or self.transaction_fd is None:
            fail("bound transaction descriptors unavailable before target publication")
        parent_fd = self.services_fd
        parent_info = os.fstat(parent_fd)
        try:
            visible_parent = os.stat(
                "services", dir_fd=self.lock_fd, follow_symlinks=False,
            )
            if (
                not stat.S_ISDIR(parent_info.st_mode)
                or not same_inode(parent_info, visible_parent)
                or (
                    self.services_stat is not None
                    and not same_inode(parent_info, self.services_stat)
                )
            ):
                fail("services directory changed before target publication")
            self.controls.hook("before-target-native-publish")
            try:
                self.native.no_replace_at(
                    self.transaction_fd, staged.name,
                    self.services_fd, self.target.name,
                )
            except OSError as error:
                fail(f"target publication failed without overwrite: {error}")
            self.target_visible = True
            try:
                visible = os.stat(
                    self.target.name, dir_fd=self.services_fd, follow_symlinks=False,
                )
            except FileNotFoundError:
                fail("published target disappeared before ownership verification")
            if (
                self.published_stat is None
                or not stat.S_ISDIR(visible.st_mode)
                or not same_inode(self.published_stat, visible)
                or not same_inode(
                    parent_info,
                    os.stat("services", dir_fd=self.lock_fd, follow_symlinks=False),
                )
            ):
                fail("published target ownership could not be proven")
            self.controls.hook("after-target-ownership-before-parent-fsync")
            try:
                self.controls.fsync_target_parent(parent_fd)
            except OSError as error:
                fail(f"target parent directory fsync failed after publication: {error}")
            try:
                after_fsync_parent = os.stat(
                    "services", dir_fd=self.lock_fd, follow_symlinks=False,
                )
                after_fsync_target = os.stat(
                    self.target.name, dir_fd=self.services_fd, follow_symlinks=False,
                )
            except FileNotFoundError:
                fail("services directory changed during target publication")
            if (
                not same_inode(parent_info, after_fsync_parent)
                or self.published_stat is None
                or not same_inode(self.published_stat, after_fsync_target)
            ):
                fail("services directory changed during target publication")
            self.controls.hook("after-target-parent-fsync-before-commit")
            self.controls.crash("after-target-parent-fsync")
            self.committed = True
        finally:
            pass

    def metadata_binding(self, live: Path) -> tuple[int, str]:
        if live == self.root_pom:
            if self.lock_fd is None:
                fail("repository descriptor unavailable for root POM transaction")
            return self.lock_fd, "pom.xml"
        if live == self.catalog:
            if self.architecture_fd is None:
                fail("architecture descriptor unavailable for catalog transaction")
            return self.architecture_fd, "service-catalog.yaml"
        fail(f"unrecognized metadata transaction path: {live}")

    def guarded_exchange(
        self,
        label: str,
        left_fd: int,
        left_name: str,
        left_expected_hash: str,
        right_fd: int,
        right_name: str,
        right_expected_hash: str,
        after_exchange_hook: Optional[str] = None,
    ) -> None:
        left_before = os.stat(left_name, dir_fd=left_fd, follow_symlinks=False)
        right_before = os.stat(right_name, dir_fd=right_fd, follow_symlinks=False)
        exchanged = False
        try:
            self.native.exchange_at(left_fd, left_name, right_fd, right_name)
            exchanged = True
            left_swapped = os.stat(left_name, dir_fd=left_fd, follow_symlinks=False)
            right_swapped = os.stat(right_name, dir_fd=right_fd, follow_symlinks=False)
            if (
                not same_inode(left_swapped, right_before)
                or not same_inode(right_swapped, left_before)
            ):
                raise OSError(f"{label} atomic exchange did not preserve entry identities")
            if after_exchange_hook:
                self.controls.hook(after_exchange_hook)
            left_current = os.stat(left_name, dir_fd=left_fd, follow_symlinks=False)
            right_current = os.stat(right_name, dir_fd=right_fd, follow_symlinks=False)
            if (
                not same_inode(left_current, right_before)
                or not same_inode(right_current, left_before)
            ):
                raise OSError(f"{label} exchanged entries changed before verification")
            if not stat.S_ISREG(left_current.st_mode) or not stat.S_ISREG(right_current.st_mode):
                raise OSError(f"{label} exchanged entry is not a regular file")
            if (
                content_hash(read_bytes_at(left_fd, left_name)) != right_expected_hash
                or content_hash(read_bytes_at(right_fd, right_name)) != left_expected_hash
            ):
                raise OSError(f"{label} exchanged bytes differ from the expected identities")
            os.fsync(left_fd)
            if right_fd != left_fd:
                os.fsync(right_fd)
            return
        except Exception as exchange_error:
            if not exchanged:
                raise
            try:
                left_current = os.stat(left_name, dir_fd=left_fd, follow_symlinks=False)
                right_current = os.stat(right_name, dir_fd=right_fd, follow_symlinks=False)
                if (
                    not same_inode(left_current, right_before)
                    or not same_inode(right_current, left_before)
                ):
                    raise OSError(
                        f"{label} entries changed before compensating exchange"
                    )
                self.native.exchange_at(left_fd, left_name, right_fd, right_name)
                left_restored = os.stat(left_name, dir_fd=left_fd, follow_symlinks=False)
                right_restored = os.stat(right_name, dir_fd=right_fd, follow_symlinks=False)
                if (
                    not same_inode(left_restored, left_before)
                    or not same_inode(right_restored, right_before)
                ):
                    raise OSError(
                        f"{label} compensating exchange did not restore entry identities"
                    )
                os.fsync(left_fd)
                if right_fd != left_fd:
                    os.fsync(right_fd)
                if after_exchange_hook:
                    self.controls.hook(after_exchange_hook.replace(
                        "native-exchange-before-inspection",
                        "safe-exchange-compensation",
                    ))
            except Exception as compensation_error:
                self.recovery_errors.append(
                    f"{label} exchange inspection failed: {exchange_error}"
                )
                self.recovery_errors.append(
                    f"{label} compensating exchange failed: {compensation_error}"
                )
                fail(
                    f"{label} exchange failed and could not be safely compensated: "
                    f"{exchange_error}; compensation error: {compensation_error}"
                )
            fail(f"{label} exchange inspection failed and was safely compensated: {exchange_error}")

    def exchange_forward(
        self,
        label: str,
        live: Path,
        slot: Path,
        original: bytes,
        intended: bytes,
    ) -> None:
        if self.transaction_fd is None:
            fail("transaction descriptor unavailable for metadata exchange")
        live_fd, live_name = self.metadata_binding(live)
        hook_label = "pom" if live == self.root_pom else "catalog"
        self.guarded_exchange(
            label,
            live_fd,
            live_name,
            content_hash(original),
            self.transaction_fd,
            slot.name,
            content_hash(intended),
            f"after-{hook_label}-native-exchange-before-inspection",
        )
        applied_attribute = "pom_replaced" if live == self.root_pom else "catalog_replaced"
        setattr(self, applied_attribute, True)

    def ensure_intended(self, label: str, live: Path, intended: bytes) -> None:
        live_fd, live_name = self.metadata_binding(live)
        info = os.stat(live_name, dir_fd=live_fd, follow_symlinks=False)
        if not stat.S_ISREG(info.st_mode) or read_bytes_at(live_fd, live_name) != intended:
            fail(f"{label} changed after transaction publication")

    def commit(self, staged: Path, next_pom: Path, next_catalog: Path) -> None:
        self.controls.hook("before-metadata-compare")
        self.verify_metadata()
        self.controls.hook("before-final-publish")

        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, TRANSACTION_SIGNALS)
        try:
            self.verify_metadata()
            self.controls.hook("after-final-metadata-check-before-pom-publish")
            pom_intended = next_pom.read_bytes()
            catalog_intended = next_catalog.read_bytes()
            self.exchange_forward(
                "root pom.xml", self.root_pom, next_pom, self.pom_snapshot, pom_intended,
            )
            self.write_journal_state("POM_EXCHANGED")
            self.controls.hook("after-pom-publish")
            self.controls.crash("after-pom-publish")
            self.ensure_intended("root pom.xml", self.root_pom, pom_intended)
            self.exchange_forward(
                "service catalog", self.catalog, next_catalog,
                self.catalog_snapshot, catalog_intended,
            )
            self.write_journal_state("CATALOG_EXCHANGED")
            self.controls.hook("after-catalog-publish")
            self.controls.crash("after-catalog-publish")
            self.ensure_intended("root pom.xml", self.root_pom, pom_intended)
            self.ensure_intended("service catalog", self.catalog, catalog_intended)
            self.controls.fail_after_catalog_replace()
            self.write_journal_state("PUBLISHING")
            self.controls.hook("metadata-committed-before-final-publish")
            self.publish_committed_target(staged)
            self.controls.crash("after-target-publish")
            self.write_journal_state("COMMITTED")
            self.controls.crash("after-journal-committed")
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)

        self.controls.hook("after-target-publish")

    def restore_file(
        self,
        label: str,
        destination: Path,
        slot: Path,
        original: bytes,
        intended_hash: str,
    ) -> None:
        if self.transaction_dir is None:
            self.recovery_errors.append(f"{label} restore unavailable: transaction directory missing")
            return
        if self.transaction_fd is None:
            self.recovery_errors.append(
                f"{label} rollback unavailable: transaction descriptor missing"
            )
            return
        attempt_errors: list[str] = []
        destination_fd, destination_name = self.metadata_binding(destination)
        for attempt in (1, 2):
            try:
                self.controls.before_restore(label)
                self.guarded_exchange(
                    f"{label} rollback",
                    destination_fd,
                    destination_name,
                    intended_hash,
                    self.transaction_fd,
                    slot.name,
                    content_hash(original),
                )
                if label == "pom":
                    self.pom_replaced = False
                else:
                    self.catalog_replaced = False
                self.rollback_completed = True
                if attempt_errors:
                    self.rollback_notes.extend(attempt_errors)
                return
            except Exception as error:
                attempt_errors.append(f"{label} restore attempt {attempt} failed: {error}")
                if self.recovery_errors:
                    break
        self.recovery_errors.extend(attempt_errors)

    def rollback(self) -> None:
        if self.transaction_dir is None:
            if self.pom_replaced or self.catalog_replaced:
                self.recovery_errors.append("rollback unavailable: transaction directory missing")
            return
        if self.target_visible and not self.committed:
            self.recovery_errors.append(
                "visible target and intended metadata preserved for durable commit recovery"
            )
            return
        if self.catalog_replaced:
            self.restore_file(
                "catalog", self.catalog, self.transaction_dir / "catalog.next",
                self.catalog_snapshot, str(self.journal_data["catalogIntendedSha256"]),
            )
        if self.pom_replaced:
            self.restore_file(
                "pom", self.root_pom, self.transaction_dir / "pom.next",
                self.pom_snapshot, str(self.journal_data["pomIntendedSha256"]),
            )

    def release_lock(self) -> None:
        self.controls.hook("before-lock-cleanup-unlink")
        for attribute, label in (
            ("transaction_fd", "transaction"),
            ("services_fd", "services"),
            ("architecture_fd", "architecture"),
            ("stable_holding_fd", "stable holding root"),
            ("stable_holding_parent_fd", "stable holding parent"),
            ("lock_fd", "lock"),
        ):
            descriptor = getattr(self, attribute)
            if descriptor is None:
                continue
            try:
                os.close(descriptor)
            except OSError as error:
                self.recovery_errors.append(f"{label} descriptor close failed: {error}")
            setattr(self, attribute, None)
        self.lock_fd = None

    def clean_staging(self) -> None:
        if self.transaction_dir is None or self.transaction_name is None:
            return
        if self.lock_fd is None:
            self.recovery_errors.append("transaction cleanup skipped: repository descriptor missing")
            return
        source = self.transaction_dir
        source_name = self.transaction_name
        expected = self.transaction_stat
        quarantine_name = f".create-service.cleanup-{secrets.token_hex(16)}"
        quarantine = self.repo_root / quarantine_name
        try:
            visible = os.stat(source_name, dir_fd=self.lock_fd, follow_symlinks=False)
            if expected is not None and not same_inode(expected, visible):
                self.recovery_errors.append("transaction cleanup skipped: path ownership changed")
                return
            self.native.no_replace_at(
                self.lock_fd, source_name, self.lock_fd, quarantine_name,
            )
            moved = os.stat(quarantine_name, dir_fd=self.lock_fd, follow_symlinks=False)
            if expected is not None and not same_inode(expected, moved):
                self.native.no_replace_at(
                    self.lock_fd, quarantine_name, self.lock_fd, source_name,
                )
                self.recovery_errors.append("transaction cleanup skipped: moved inode ownership changed")
                return
            self.transaction_dir = quarantine
            self.transaction_name = quarantine_name
            self.transaction_stat = moved
            if self.transaction_fd is not None:
                os.close(self.transaction_fd)
                self.transaction_fd = None
            shutil.rmtree(quarantine)
            os.fsync(self.lock_fd)
            self.transaction_dir = None
            self.transaction_name = None
            self.transaction_stat = None
        except FileNotFoundError:
            if self.transaction_fd is not None:
                os.close(self.transaction_fd)
                self.transaction_fd = None
            self.transaction_dir = None
            self.transaction_name = None
            self.transaction_stat = None
        except OSError as error:
            self.recovery_errors.append(f"transaction cleanup failed: {error}")

    def write_journal(self, original_error: Exception) -> Optional[Path]:
        if self.transaction_dir is None:
            return None
        self.journal_data["originalError"] = str(original_error)
        self.journal_data["recoveryErrors"] = list(self.recovery_errors)
        try:
            current_stage = str(self.journal_data.get("stage", "RECOVERY_REQUIRED"))
            retained_stage = (
                "PUBLISHING"
                if self.target_visible and current_stage == "PUBLISHING"
                else "RECOVERY_REQUIRED"
            )
            self.write_journal_state(retained_stage)
            return self.journal_path
        except OSError as error:
            print(f"failed to write recovery journal in {self.transaction_dir}: {error}", file=sys.stderr)
            return None

    def write_existing_journal(
        self,
        directory: Path,
        data: dict[str, object],
        directory_fd: Optional[int] = None,
        parent_fd: Optional[int] = None,
    ) -> None:
        temporary_name = f".RECOVERY.{secrets.token_hex(8)}.tmp"
        payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
        if directory_fd is not None:
            write_new_at(directory_fd, temporary_name, payload)
            os.replace(
                temporary_name, "RECOVERY.json",
                src_dir_fd=directory_fd, dst_dir_fd=directory_fd,
            )
            os.fsync(directory_fd)
            durable_parent = parent_fd if parent_fd is not None else self.lock_fd
            if durable_parent is not None:
                os.fsync(durable_parent)
            return
        temporary = directory / temporary_name
        write_new(temporary, payload)
        os.replace(temporary, directory / "RECOVERY.json")
        fsync_directory(directory)
        fsync_directory(self.repo_root)

    def load_pending_journal(
        self, directory: Path, directory_fd: Optional[int] = None,
    ) -> dict[str, object]:
        journal = directory / "RECOVERY.json"
        if directory_fd is None:
            ensure_regular(journal, f"recovery journal {journal}")
            journal_bytes = journal.read_bytes()
        else:
            try:
                journal_info = os.stat(
                    "RECOVERY.json", dir_fd=directory_fd, follow_symlinks=False,
                )
                if not stat.S_ISREG(journal_info.st_mode):
                    fail(f"recovery journal {journal} must be a regular file")
                journal_bytes = read_bytes_at(directory_fd, "RECOVERY.json")
            except FileNotFoundError:
                fail(f"recovery journal {journal} must be a regular file")
        try:
            data = json.loads(journal_bytes.decode("utf-8"), object_pairs_hook=reject_duplicates)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            fail(f"recovery journal is invalid at {journal}: {error}")
        required = {
            "version", "ownerToken", "service", "name", "port", "schema", "target", "stage",
            "pomOriginalSha256", "catalogOriginalSha256", "pomIntendedSha256",
            "catalogIntendedSha256", "stagedDevice", "stagedInode", "recoveryErrors",
            "servicesDevice", "servicesInode",
        }
        if not isinstance(data, dict) or not required.issubset(data):
            fail(f"recovery journal fields are invalid at {journal}")
        name = data["name"]
        port = data["port"]
        schema = data["schema"]
        owner_token = data["ownerToken"]
        if (
            type(data["version"]) is not int
            or data["version"] != 2
            or not isinstance(owner_token, str)
            or re.fullmatch(r"[0-9a-f]{64}", owner_token) is None
            or not isinstance(name, str)
            or re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*", name) is None
            or any(segment in JAVA_RESERVED for segment in name.split("-"))
            or type(port) is not int
            or not 1024 <= port <= 65535
            or not isinstance(schema, str)
            or re.fullmatch(r"[a-z][a-z0-9_]*_db", schema) is None
        ):
            fail(f"recovery journal identity is invalid at {journal}")
        expected_target = Path("services") / f"{name}-service"
        target = Path(str(data["target"]))
        valid_hash = re.compile(r"[0-9a-f]{64}")
        if (
            target != expected_target
            or target.is_absolute()
            or data["service"] != f"{name}-service"
            or data["stage"] not in {
                "PREPARING", "PREPARED", "POM_EXCHANGED", "CATALOG_EXCHANGED",
                "PUBLISHING", "COMMITTED", "RECOVERY_REQUIRED",
            }
            or valid_hash.fullmatch(str(data["pomOriginalSha256"])) is None
            or valid_hash.fullmatch(str(data["catalogOriginalSha256"])) is None
            or (
                data["pomIntendedSha256"] is not None
                and valid_hash.fullmatch(str(data["pomIntendedSha256"])) is None
            )
            or (
                data["catalogIntendedSha256"] is not None
                and valid_hash.fullmatch(str(data["catalogIntendedSha256"])) is None
            )
            or not isinstance(data["recoveryErrors"], list)
            or any(not isinstance(error, str) for error in data["recoveryErrors"])
            or type(data["servicesDevice"]) is not int
            or type(data["servicesInode"]) is not int
        ):
            fail(f"recovery journal target is invalid at {journal}")
        return data

    def recover_file(
        self,
        directory: Path,
        transaction_fd: int,
        data: dict[str, object],
        label: str,
        live: Path,
    ) -> Optional[str]:
        original_key = f"{label}OriginalSha256"
        intended_key = f"{label}IntendedSha256"
        original_hash = data[original_key]
        intended_hash = data[intended_key]
        live_fd, live_name = self.metadata_binding(live)
        try:
            live_hash = content_hash(read_bytes_at(live_fd, live_name))
        except (FileNotFoundError, OSError) as error:
            return f"{label} recovery inspection failed: {error}"
        if live_hash == original_hash:
            return None
        if intended_hash is None or live_hash != intended_hash:
            return f"{label} recovery conflict: live bytes are external and were preserved"
        slot = directory / f"{label}.next"
        try:
            if content_hash(read_bytes_at(transaction_fd, slot.name)) != original_hash:
                return f"{label} recovery conflict: original exchange slot changed"
            self.guarded_exchange(
                f"{label} recovery",
                live_fd,
                live_name,
                str(intended_hash),
                transaction_fd,
                slot.name,
                str(original_hash),
            )
            return None
        except Exception as error:
            return f"{label} recovery exchange failed: {error}"

    def remove_cleanup_directory_entries(self, directory_fd: int) -> None:
        names = sorted(os.listdir(directory_fd))
        if any(name.startswith(".cleanup-entry-") for name in names):
            raise CleanupConflict("an unclaimed cleanup entry was retained from an earlier conflict")
        entries = {
            name: os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            for name in names
        }
        for name, expected in entries.items():
            self.remove_cleanup_entry(directory_fd, name, expected)
            os.fsync(directory_fd)
        if os.listdir(directory_fd):
            raise CleanupConflict("entries appeared concurrently inside a cleanup directory")

    def remove_cleanup_entry(
        self,
        parent_fd: int,
        name: str,
        expected: os.stat_result,
        *,
        directory_cleaner: Optional[Callable[[int], None]] = None,
    ) -> None:
        claimed_name = f".cleanup-entry-{secrets.token_hex(16)}"
        self.native.no_replace_at(parent_fd, name, parent_fd, claimed_name)
        moved = os.stat(claimed_name, dir_fd=parent_fd, follow_symlinks=False)
        if not same_inode(expected, moved):
            try:
                self.native.no_replace_at(parent_fd, claimed_name, parent_fd, name)
            except Exception as restore_error:
                raise CleanupConflict(
                    f"cleanup entry {name} changed while claiming it; "
                    f"the replacement was retained as {claimed_name}: {restore_error}"
                ) from restore_error
            raise CleanupConflict(f"cleanup entry {name} changed while claiming it")
        try:
            if stat.S_ISDIR(moved.st_mode):
                child_fd = os.open(
                    claimed_name,
                    os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                    dir_fd=parent_fd,
                )
                try:
                    opened = os.fstat(child_fd)
                    visible = os.stat(
                        claimed_name, dir_fd=parent_fd, follow_symlinks=False,
                    )
                    if not same_inode(opened, visible) or not same_inode(opened, moved):
                        raise CleanupConflict(f"cleanup directory {name} changed after claim")
                    if directory_cleaner is None:
                        self.remove_cleanup_directory_entries(child_fd)
                    else:
                        directory_cleaner(child_fd)
                    os.fsync(child_fd)
                    visible = os.stat(
                        claimed_name, dir_fd=parent_fd, follow_symlinks=False,
                    )
                    if not same_inode(opened, visible):
                        raise CleanupConflict(f"cleanup directory {name} changed before removal")
                finally:
                    os.close(child_fd)
                os.rmdir(claimed_name, dir_fd=parent_fd)
            else:
                visible = os.stat(
                    claimed_name, dir_fd=parent_fd, follow_symlinks=False,
                )
                if not same_inode(moved, visible):
                    raise CleanupConflict(f"cleanup entry {name} changed before removal")
                os.unlink(claimed_name, dir_fd=parent_fd)
        except Exception as original_error:
            try:
                os.stat(claimed_name, dir_fd=parent_fd, follow_symlinks=False)
            except FileNotFoundError:
                raise original_error
            try:
                self.native.no_replace_at(parent_fd, claimed_name, parent_fd, name)
            except Exception as restore_error:
                raise CleanupConflict(
                    f"cleanup entry {name} could not be restored after {original_error}; "
                    f"retained as {claimed_name}: {restore_error}"
                ) from original_error
            raise

    @staticmethod
    def cleanup_top_level_name_is_allowed(name: str, data: dict[str, object]) -> bool:
        allowed = {
            "RECOVERY.json",
            "pom.backup",
            "catalog.backup",
            "pom.next",
            "catalog.next",
            f"{data['name']}-service",
        }
        return name in allowed or (
            name.startswith(".RECOVERY.") and name.endswith(".tmp")
        )

    def retain_cleanup_evidence_after_finalization(
        self,
        quarantine_name: str,
        transaction_fd: int,
        expected: os.stat_result,
        data: dict[str, object],
    ) -> Path:
        if self.lock_fd is None:
            fail("repository descriptor unavailable for cleanup evidence retention")
        payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
        quarantine = self.repo_root / quarantine_name
        same_directory_error: Optional[Exception] = None
        try:
            visible = os.stat(
                quarantine_name, dir_fd=self.lock_fd, follow_symlinks=False,
            )
            if not same_inode(expected, visible):
                raise OSError("cleanup quarantine ownership changed before journal retention")
            try:
                journal_info = os.stat(
                    "RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False,
                )
            except FileNotFoundError:
                write_new_at(transaction_fd, "RECOVERY.json", payload)
            else:
                if (
                    not stat.S_ISREG(journal_info.st_mode)
                    or read_bytes_at(transaction_fd, "RECOVERY.json") != payload
                ):
                    raise OSError("cleanup quarantine contains conflicting recovery evidence")
            os.fsync(transaction_fd)
            os.fsync(self.lock_fd)
            return quarantine / "RECOVERY.json"
        except Exception as error:
            same_directory_error = error
        try:
            return self.rematerialize_cleanup_evidence(data) / "RECOVERY.json"
        except Exception as evidence_error:
            raise OSError(
                f"cleanup journal retention failed in {quarantine}: {same_directory_error}; "
                f"fallback evidence retention also failed: {evidence_error}"
            ) from evidence_error

    @staticmethod
    def same_entry_type(first: os.stat_result, second: os.stat_result) -> bool:
        return stat.S_IFMT(first.st_mode) == stat.S_IFMT(second.st_mode)

    def restore_bound_cleanup_journal(
        self,
        transaction_fd: int,
        data: dict[str, object],
    ) -> None:
        payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
        try:
            journal_info = os.stat(
                "RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False,
            )
        except FileNotFoundError:
            try:
                write_new_at(transaction_fd, "RECOVERY.json", payload)
            except FileExistsError:
                journal_info = os.stat(
                    "RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False,
                )
            else:
                journal_info = os.stat(
                    "RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False,
                )
        if (
            not stat.S_ISREG(journal_info.st_mode)
            or read_bytes_at(transaction_fd, "RECOVERY.json") != payload
        ):
            raise CleanupConflict(
                "bound cleanup directory contains conflicting recovery evidence"
            )
        os.fsync(transaction_fd)

    def locate_bound_repo_directory(self, expected: os.stat_result) -> str:
        if self.lock_fd is None:
            fail("repository descriptor unavailable while locating cleanup transaction")
        matches: list[str] = []
        for name in os.listdir(self.lock_fd):
            try:
                visible = os.stat(
                    name, dir_fd=self.lock_fd, follow_symlinks=False,
                )
            except FileNotFoundError:
                continue
            if stat.S_ISDIR(visible.st_mode) and same_inode(expected, visible):
                matches.append(name)
        if len(matches) != 1:
            raise CleanupConflict(
                "bound cleanup transaction has "
                f"{len(matches)} repository directory names"
            )
        return matches[0]

    def retain_top_level_cleanup_identity_loss(
        self,
        transaction_fd: int,
        expected: os.stat_result,
        data: dict[str, object],
        replacement_name: Optional[str],
        replacement_info: Optional[os.stat_result],
        reason: str,
    ) -> None:
        if self.lock_fd is None:
            fail("repository descriptor unavailable during cleanup identity recovery")
        try:
            self.restore_bound_cleanup_journal(transaction_fd, data)
            bound_name = self.locate_bound_repo_directory(expected)
        except Exception as journal_error:
            try:
                evidence = self.rematerialize_cleanup_evidence(data) / "RECOVERY.json"
            except Exception as evidence_error:
                fail(
                    f"{reason}; bound journal recovery failed: {journal_error}; "
                    f"fallback evidence recovery also failed: {evidence_error}"
                )
            fail(
                f"{reason}; bound journal recovery failed: {journal_error}; "
                f"fallback evidence retained at {evidence}"
            )

        preserved_name: Optional[str] = None
        if replacement_name is not None and replacement_info is not None:
            preserved_name = f".create-service-preserved-{secrets.token_hex(16)}"
            try:
                self.native.no_replace_at(
                    self.lock_fd, replacement_name,
                    self.lock_fd, preserved_name,
                )
                preserved = os.stat(
                    preserved_name, dir_fd=self.lock_fd, follow_symlinks=False,
                )
                if (
                    not same_inode(replacement_info, preserved)
                    or not self.same_entry_type(replacement_info, preserved)
                ):
                    raise CleanupConflict(
                        "top-level cleanup replacement changed while being preserved"
                    )
            except Exception as preserve_error:
                try:
                    preserved = os.stat(
                        preserved_name, dir_fd=self.lock_fd, follow_symlinks=False,
                    )
                except (FileNotFoundError, TypeError):
                    pass
                else:
                    try:
                        self.native.no_replace_at(
                            self.lock_fd, preserved_name,
                            self.lock_fd, replacement_name,
                        )
                    except Exception as restore_error:
                        fail(
                            f"{reason}; replacement preservation failed: {preserve_error}; "
                            f"replacement retained as {preserved_name}, but restoring its "
                            f"pathname also failed: {restore_error}; bound journal remains "
                            f"at {bound_name}/RECOVERY.json"
                        )
                fail(
                    f"{reason}; replacement preservation failed: {preserve_error}; "
                    f"bound journal remains at {bound_name}/RECOVERY.json"
                )

        recovery_name = f".create-service.cleanup-recovered-{secrets.token_hex(16)}"
        try:
            self.native.no_replace_at(
                self.lock_fd, bound_name,
                self.lock_fd, recovery_name,
            )
            registered = os.stat(
                recovery_name, dir_fd=self.lock_fd, follow_symlinks=False,
            )
            if not stat.S_ISDIR(registered.st_mode) or not same_inode(expected, registered):
                raise CleanupConflict(
                    "re-registered cleanup path does not identify the bound transaction"
                )
            os.fsync(self.lock_fd)
        except Exception as registration_error:
            fail(
                f"{reason}; replacement retained as {preserved_name or '<unchanged>'}; "
                f"bound journal restored at {bound_name}/RECOVERY.json, but safe recovery "
                f"registration failed: {registration_error}"
            )
        fail(
            f"{reason}; replacement retained as {preserved_name or '<unchanged>'}; "
            f"recovery journal re-registered at {recovery_name}/RECOVERY.json"
        )

    def claim_top_level_cleanup_directory(
        self,
        current_name: str,
        transaction_fd: int,
        expected: os.stat_result,
        data: dict[str, object],
    ) -> tuple[str, os.stat_result]:
        if self.lock_fd is None:
            fail("repository descriptor unavailable during cleanup finalization")
        finalize_name = f".create-service.cleanup-finalize-{secrets.token_hex(16)}"
        self.native.no_replace_at(
            self.lock_fd, current_name,
            self.lock_fd, finalize_name,
        )
        claimed = os.stat(
            finalize_name, dir_fd=self.lock_fd, follow_symlinks=False,
        )
        if not stat.S_ISDIR(claimed.st_mode) or not same_inode(expected, claimed):
            self.retain_top_level_cleanup_identity_loss(
                transaction_fd,
                expected,
                data,
                finalize_name,
                claimed,
                "top-level cleanup identity changed during atomic claim",
            )
        os.fsync(self.lock_fd)
        return finalize_name, claimed

    def write_stable_holding_journal(
        self,
        holding_fd: int,
        data: dict[str, object],
    ) -> None:
        if self.stable_holding_fd is None:
            fail("stable holding root descriptor unavailable for journal persistence")
        temporary_name = f".RECOVERY.{secrets.token_hex(8)}.tmp"
        payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
        write_new_at(holding_fd, temporary_name, payload)
        os.replace(
            temporary_name,
            "RECOVERY.json",
            src_dir_fd=holding_fd,
            dst_dir_fd=holding_fd,
        )
        os.fsync(holding_fd)
        os.fsync(self.stable_holding_fd)

    def create_stable_holding_child(
        self,
        data: dict[str, object],
    ) -> tuple[str, Path, int, os.stat_result]:
        if self.stable_holding_fd is None or self.stable_holding_stat is None:
            fail("stable holding root is unavailable before cleanup initialization")
        owner_token = str(data.get("ownerToken", ""))
        if re.fullmatch(r"[0-9a-f]{64}", owner_token) is None:
            fail("cleanup transaction owner token is invalid")
        child_name = f".create-service.cleanup-{owner_token}"
        child = self.stable_holding_root / child_name
        try:
            os.mkdir(child_name, 0o700, dir_fd=self.stable_holding_fd)
        except FileExistsError:
            fail(f"stable holding transaction already exists: {child}")
        child_fd: Optional[int] = None
        child_info: Optional[os.stat_result] = None
        initialization = dict(data)
        initialization["stage"] = "PREPARING"
        initialization["holdingTargetStage"] = data.get("stage")
        initialization["holdingState"] = "PREPARING"
        initialization_error: Optional[Exception] = None
        cleanup_error: Optional[Exception] = None
        try:
            child_fd = os.open(
                child_name,
                os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                dir_fd=self.stable_holding_fd,
            )
            child_info = os.fstat(child_fd)
            visible = os.stat(
                child_name,
                dir_fd=self.stable_holding_fd,
                follow_symlinks=False,
            )
            if (
                not stat.S_ISDIR(child_info.st_mode)
                or not same_inode(child_info, visible)
                or child_info.st_dev != self.stable_holding_stat.st_dev
                or child_info.st_uid != os.geteuid()
                or stat.S_IMODE(child_info.st_mode) != 0o700
            ):
                fail("stable holding transaction child failed ownership validation")
            payload = (
                json.dumps(initialization, indent=2, sort_keys=True) + "\n"
            ).encode("utf-8")
            temporary_name = f".RECOVERY.{secrets.token_hex(8)}.tmp"
            write_new_at(child_fd, temporary_name, payload)
            os.replace(
                temporary_name,
                "RECOVERY.json",
                src_dir_fd=child_fd,
                dst_dir_fd=child_fd,
            )
            os.fsync(child_fd)
            os.fsync(self.stable_holding_fd)
            return child_name, child, child_fd, child_info
        except Exception as error:
            initialization_error = error

        try:
            if child_fd is None:
                child_fd = os.open(
                    child_name,
                    os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                    dir_fd=self.stable_holding_fd,
                )
                child_info = os.fstat(child_fd)
            remove_directory_contents_by_fd(child_fd)
            visible = os.stat(
                child_name,
                dir_fd=self.stable_holding_fd,
                follow_symlinks=False,
            )
            if child_info is None or not same_inode(child_info, visible):
                raise OSError("stable holding initialization child changed during cleanup")
            os.rmdir(child_name, dir_fd=self.stable_holding_fd)
            os.fsync(self.stable_holding_fd)
        except Exception as error:
            cleanup_error = error
            try:
                if child_fd is not None:
                    self.restore_bound_cleanup_journal(child_fd, initialization)
                    os.fsync(self.stable_holding_fd)
            except Exception as evidence_error:
                raise OSError(
                    "stable holding root initialization failed: "
                    f"{initialization_error}; incomplete child cleanup failed: {cleanup_error}; "
                    f"initialization evidence retention also failed: {evidence_error}"
                ) from initialization_error
        finally:
            if child_fd is not None:
                os.close(child_fd)
        if cleanup_error is not None:
            raise OSError(
                "stable holding root initialization failed: "
                f"{initialization_error}; incomplete child retained with recovery evidence "
                f"at {child / 'RECOVERY.json'} after cleanup failed: {cleanup_error}"
            ) from initialization_error
        raise OSError(
            f"stable holding root initialization failed: {initialization_error}"
        ) from initialization_error

    def preserve_repo_entry_from_holding(
        self,
        holding_fd: int,
        child_name: str,
        expected: os.stat_result,
    ) -> str:
        if self.lock_fd is None:
            fail("repository descriptor unavailable while preserving cleanup replacement")
        preserved_name = f".create-service-preserved-{secrets.token_hex(16)}"
        self.native.no_replace_at(
            holding_fd, child_name, self.lock_fd, preserved_name,
        )
        preserved = os.stat(
            preserved_name, dir_fd=self.lock_fd, follow_symlinks=False,
        )
        if not same_inode(expected, preserved) or not self.same_entry_type(expected, preserved):
            raise CleanupConflict(
                "cleanup replacement changed while moving into its preservation path"
            )
        os.fsync(self.lock_fd)
        return preserved_name

    def remove_bound_cleanup_through_holding(
        self,
        public_name: str,
        transaction_fd: int,
        expected: os.stat_result,
        data: dict[str, object],
        holding_name: str,
        holding: Path,
        holding_fd: int,
        holding_info: os.stat_result,
    ) -> None:
        if self.lock_fd is None or self.stable_holding_fd is None:
            fail("repository descriptor unavailable during cleanup holding finalization")
        child_name = "transaction"
        try:
            # Linux and Darwin provide no conditional rmdir-by-inode primitive.  The
            # repository-visible transaction is therefore only moved into this durable,
            # mode-0700 stable-root child.  The stable root itself is never unlinked;
            # journal-last removal below is confined to this bound ownership namespace.
            self.native.no_replace_at(
                self.lock_fd, public_name, holding_fd, child_name,
            )
            claimed = os.stat(
                child_name, dir_fd=holding_fd, follow_symlinks=False,
            )
            if not stat.S_ISDIR(claimed.st_mode) or not same_inode(expected, claimed):
                preserved_name = self.preserve_repo_entry_from_holding(
                    holding_fd, child_name, claimed,
                )
                bound_name = self.locate_bound_repo_directory(expected)
                self.native.no_replace_at(
                    self.lock_fd, bound_name, holding_fd, child_name,
                )
                bound = os.stat(
                    child_name, dir_fd=holding_fd, follow_symlinks=False,
                )
                if not stat.S_ISDIR(bound.st_mode) or not same_inode(expected, bound):
                    raise CleanupConflict(
                        "bound cleanup transaction changed while entering holding"
                    )
                os.fsync(holding_fd)
                os.fsync(self.lock_fd)
                self.write_stable_holding_journal(holding_fd, data)
                fail(
                    "top-level cleanup identity changed before private holding claim; "
                    f"replacement retained as {preserved_name}; recovery journal "
                    f"retained at {holding / 'RECOVERY.json'}"
                )

            opened = os.fstat(transaction_fd)
            if not same_inode(opened, claimed) or not same_inode(opened, expected):
                raise CleanupConflict(
                    "cleanup holding child does not identify the bound transaction"
                )
            os.fsync(holding_fd)
            os.fsync(self.lock_fd)
            self.write_stable_holding_journal(holding_fd, data)

            try:
                late = os.stat(
                    public_name, dir_fd=self.lock_fd, follow_symlinks=False,
                )
            except FileNotFoundError:
                late = None
            if late is not None:
                late_preserved = f".create-service-preserved-{secrets.token_hex(16)}"
                self.native.no_replace_at(
                    self.lock_fd, public_name, self.lock_fd, late_preserved,
                )
                retained = os.stat(
                    late_preserved, dir_fd=self.lock_fd, follow_symlinks=False,
                )
                os.fsync(self.lock_fd)
                fail(
                    "entry appeared at the released cleanup pathname and was preserved "
                    f"as {late_preserved} ({retained.st_dev}:{retained.st_ino}); "
                    f"recovery journal retained at {holding / 'RECOVERY.json'}"
                )

            os.unlink("RECOVERY.json", dir_fd=transaction_fd)
            os.fsync(transaction_fd)
            if os.listdir(transaction_fd):
                raise CleanupConflict(
                    "entries appeared inside the bound cleanup transaction in holding"
                )
            os.rmdir(child_name, dir_fd=holding_fd)
            os.fsync(holding_fd)

            root_journal = os.stat(
                "RECOVERY.json", dir_fd=holding_fd, follow_symlinks=False,
            )
            if not stat.S_ISREG(root_journal.st_mode):
                raise CleanupConflict("cleanup holding recovery journal changed type")
            os.unlink("RECOVERY.json", dir_fd=holding_fd)
            os.fsync(holding_fd)
            if os.listdir(holding_fd):
                raise CleanupConflict("entries appeared inside the stable cleanup child")
            visible_holding = os.stat(
                holding_name, dir_fd=self.stable_holding_fd, follow_symlinks=False,
            )
            if (
                not stat.S_ISDIR(visible_holding.st_mode)
                or not same_inode(holding_info, visible_holding)
                or not same_inode(holding_info, os.fstat(holding_fd))
            ):
                raise CleanupConflict("stable cleanup child identity changed")
            os.rmdir(holding_name, dir_fd=self.stable_holding_fd)
            os.fsync(self.stable_holding_fd)
        except Exception as error:
            try:
                visible = os.stat(
                    holding_name,
                    dir_fd=self.stable_holding_fd,
                    follow_symlinks=False,
                )
            except FileNotFoundError:
                visible = None
            if visible is not None and same_inode(holding_info, visible):
                try:
                    self.restore_bound_cleanup_journal(holding_fd, data)
                    os.fsync(self.stable_holding_fd)
                except Exception as journal_error:
                    raise OSError(
                        f"stable cleanup child failed: {error}; holding journal retention "
                        f"also failed: {journal_error}"
                    ) from error
            raise
    def remove_recovered_directory(
        self,
        directory: Path,
        transaction_fd: int,
        expected: os.stat_result,
        data: dict[str, object],
    ) -> None:
        if self.lock_fd is None:
            fail("repository descriptor unavailable for recovered transaction cleanup")
        quarantine_name = f".create-service.cleanup-{secrets.token_hex(16)}"
        self.native.no_replace_at(
            self.lock_fd, directory.name, self.lock_fd, quarantine_name,
        )
        moved = os.stat(quarantine_name, dir_fd=self.lock_fd, follow_symlinks=False)
        if not same_inode(expected, moved):
            self.native.no_replace_at(
                self.lock_fd, quarantine_name, self.lock_fd, directory.name,
            )
            fail(f"recovery transaction ownership changed at {directory}")
        quarantine = self.repo_root / quarantine_name
        os.fsync(self.lock_fd)
        try:
            names = sorted(os.listdir(transaction_fd))
            unexpected = [
                name for name in names
                if not self.cleanup_top_level_name_is_allowed(name, data)
            ]
            if unexpected:
                raise CleanupConflict(
                    f"unexpected cleanup entries were preserved: {', '.join(unexpected)}"
                )
            entries = {
                name: os.stat(name, dir_fd=transaction_fd, follow_symlinks=False)
                for name in names if name != "RECOVERY.json"
            }
            for name, entry_info in entries.items():
                self.remove_cleanup_entry(transaction_fd, name, entry_info)
                os.fsync(transaction_fd)
            if os.listdir(transaction_fd) != ["RECOVERY.json"]:
                raise CleanupConflict(
                    "entries appeared concurrently before cleanup journal finalization"
                )
        except CleanupConflict as conflict:
            errors = list(data.get("recoveryErrors", []))
            errors.append(str(conflict))
            data["stage"] = "RECOVERY_REQUIRED"
            data["recoveryErrors"] = errors
            try:
                self.write_existing_journal(quarantine, data, transaction_fd)
            except Exception as journal_error:
                fail(
                    f"recovery cleanup conflict: {conflict}; retaining the manual journal "
                    f"also failed: {journal_error}"
                )
            fail(
                f"recovery cleanup conflict; journal retained at "
                f"{quarantine / 'RECOVERY.json'}: {conflict}"
            )
        payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
        journal_info = os.stat(
            "RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False,
        )
        if (
            not stat.S_ISREG(journal_info.st_mode)
            or read_bytes_at(transaction_fd, "RECOVERY.json") != payload
        ):
            raise CleanupConflict("recovery journal changed before journal-last finalization")
        finalize_name, moved = self.claim_top_level_cleanup_directory(
            quarantine_name, transaction_fd, moved, data,
        )
        quarantine_name = finalize_name
        quarantine = self.repo_root / quarantine_name
        holding_name: Optional[str] = None
        holding: Optional[Path] = None
        holding_fd: Optional[int] = None
        holding_info: Optional[os.stat_result] = None
        try:
            holding_name, holding, holding_fd, holding_info = (
                self.create_stable_holding_child(data)
            )
            self.controls.hook("before-top-level-holding-claim")
            self.remove_bound_cleanup_through_holding(
                quarantine_name,
                transaction_fd,
                moved,
                data,
                holding_name,
                holding,
                holding_fd,
                holding_info,
            )
        except Exception as original_error:
            try:
                if holding is not None and holding_fd is not None:
                    try:
                        visible_holding = os.stat(
                            holding_name,
                            dir_fd=self.stable_holding_fd,
                            follow_symlinks=False,
                        ) if holding_name is not None else None
                    except FileNotFoundError:
                        visible_holding = None
                    if (
                        visible_holding is not None
                        and holding_info is not None
                        and same_inode(holding_info, visible_holding)
                    ):
                        evidence = holding / "RECOVERY.json"
                        self.restore_bound_cleanup_journal(holding_fd, data)
                        if self.stable_holding_fd is None:
                            fail("stable holding root descriptor unavailable")
                        os.fsync(self.stable_holding_fd)
                    else:
                        evidence = self.rematerialize_cleanup_evidence(data) / "RECOVERY.json"
                else:
                    evidence = self.retain_cleanup_evidence_after_finalization(
                        quarantine_name, transaction_fd, moved, data,
                    )
            except Exception as evidence_error:
                fail(
                    "recovered transaction journal-last finalization failed: "
                    f"{original_error}; recovery evidence retention also failed: "
                    f"{evidence_error}"
                )
            fail(
                "recovered transaction journal-last finalization failed: "
                f"{original_error}; recovery evidence retained at {evidence}"
            )
        finally:
            if holding_fd is not None:
                os.close(holding_fd)

    def rematerialize_cleanup_evidence(self, data: dict[str, object]) -> Path:
        if self.lock_fd is None:
            fail("repository descriptor unavailable for cleanup evidence retention")
        evidence_name: Optional[str] = None
        evidence: Optional[Path] = None
        evidence_fd: Optional[int] = None
        try:
            evidence_name, evidence, evidence_fd, evidence_info = (
                self.create_private_repo_directory(".create-service.cleanup-pending-")
            )
            payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
            write_new_at(evidence_fd, "RECOVERY.json", payload)
            os.fsync(evidence_fd)
            visible = os.stat(
                evidence_name, dir_fd=self.lock_fd, follow_symlinks=False,
            )
            if not same_inode(evidence_info, visible):
                raise OSError("cleanup evidence ownership changed during retention")
            os.fsync(self.lock_fd)
            return evidence
        except Exception as error:
            retained = evidence if evidence is not None else self.repo_root
            raise OSError(
                f"cleanup evidence could not be durably retained at {retained}: {error}"
            ) from error
        finally:
            if evidence_fd is not None:
                os.close(evidence_fd)

    def cleanup_existing_stable_holding(
        self,
        holding_name: str,
        holding: Path,
        holding_fd: int,
        holding_info: os.stat_result,
        transaction_fd: Optional[int],
        transaction_info: Optional[os.stat_result],
        data: dict[str, object],
    ) -> None:
        if self.stable_holding_fd is None:
            fail("stable holding root descriptor unavailable during recovery cleanup")
        try:
            self.write_stable_holding_journal(holding_fd, data)
            if transaction_fd is not None:
                if transaction_info is None:
                    raise CleanupConflict("stable holding transaction identity is unavailable")
                names = sorted(os.listdir(transaction_fd))
                unexpected = [
                    name for name in names
                    if not self.cleanup_top_level_name_is_allowed(name, data)
                ]
                if unexpected:
                    raise CleanupConflict(
                        "unexpected stable cleanup entries were preserved: "
                        + ", ".join(unexpected)
                    )
                entries = {
                    name: os.stat(name, dir_fd=transaction_fd, follow_symlinks=False)
                    for name in names if name != "RECOVERY.json"
                }
                for name, entry_info in entries.items():
                    self.remove_cleanup_entry(transaction_fd, name, entry_info)
                    os.fsync(transaction_fd)
                try:
                    journal_info = os.stat(
                        "RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False,
                    )
                except FileNotFoundError:
                    # The stable child journal is the durable journal-last authority.
                    # A previous cleanup may have removed the nested copy immediately
                    # before an injected or real directory-fsync failure.
                    journal_info = None
                if journal_info is not None:
                    if not stat.S_ISREG(journal_info.st_mode):
                        raise CleanupConflict("nested recovery journal changed type")
                    os.unlink("RECOVERY.json", dir_fd=transaction_fd)
                    os.fsync(transaction_fd)
                if os.listdir(transaction_fd):
                    raise CleanupConflict("nested stable cleanup transaction is not empty")
                visible_transaction = os.stat(
                    "transaction", dir_fd=holding_fd, follow_symlinks=False,
                )
                if not same_inode(transaction_info, visible_transaction):
                    raise CleanupConflict("nested stable cleanup transaction changed identity")
                os.rmdir("transaction", dir_fd=holding_fd)
                os.fsync(holding_fd)

            payload = (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8")
            journal_info = os.stat(
                "RECOVERY.json", dir_fd=holding_fd, follow_symlinks=False,
            )
            if (
                not stat.S_ISREG(journal_info.st_mode)
                or read_bytes_at(holding_fd, "RECOVERY.json") != payload
            ):
                raise CleanupConflict("stable holding journal changed before finalization")
            os.unlink("RECOVERY.json", dir_fd=holding_fd)
            os.fsync(holding_fd)
            if os.listdir(holding_fd):
                raise CleanupConflict("stable holding child is not empty")
            visible_holding = os.stat(
                holding_name,
                dir_fd=self.stable_holding_fd,
                follow_symlinks=False,
            )
            if not same_inode(holding_info, visible_holding):
                raise CleanupConflict("stable holding child changed before final removal")
            os.rmdir(holding_name, dir_fd=self.stable_holding_fd)
            os.fsync(self.stable_holding_fd)
        except Exception as error:
            try:
                visible = os.stat(
                    holding_name,
                    dir_fd=self.stable_holding_fd,
                    follow_symlinks=False,
                )
            except FileNotFoundError:
                visible = None
            if visible is not None and same_inode(holding_info, visible):
                try:
                    self.restore_bound_cleanup_journal(holding_fd, data)
                    os.fsync(self.stable_holding_fd)
                except Exception as evidence_error:
                    raise OSError(
                        f"stable holding cleanup failed: {error}; recovery evidence "
                        f"retention also failed: {evidence_error}"
                    ) from error
            raise

    def recover_transaction_state(
        self,
        directory: Path,
        transaction_fd: int,
        info: os.stat_result,
        data: dict[str, object],
        evidence_directory: Path,
        evidence_fd: int,
        evidence_parent_fd: int,
        stable_context: Optional[
            tuple[str, Path, int, os.stat_result, Optional[os.stat_result]]
        ] = None,
    ) -> bool:
        if self.services_fd is None:
            fail("services descriptor unavailable during recovery")
        stage = str(data["stage"])
        journal = evidence_directory / "RECOVERY.json"

        if stage == "RECOVERY_REQUIRED":
            fail(f"manual recovery required; journal retained at {journal}")

        def retain_manual(message: str) -> None:
            errors = list(data.get("recoveryErrors", []))
            errors.append(message)
            data["stage"] = "RECOVERY_REQUIRED"
            data["recoveryErrors"] = errors
            self.write_existing_journal(
                evidence_directory,
                data,
                evidence_fd,
                evidence_parent_fd,
            )
            fail(f"recovery incomplete; journal retained at {journal}: {message}")

        def metadata_state(label: str, live: Path) -> str:
            live_fd, live_name = self.metadata_binding(live)
            try:
                digest = content_hash(read_bytes_at(live_fd, live_name))
            except (FileNotFoundError, OSError):
                return "missing"
            if digest == data[f"{label}OriginalSha256"]:
                return "original"
            intended = data[f"{label}IntendedSha256"]
            if intended is not None and digest == intended:
                return "intended"
            return "external"

        def finalize() -> None:
            if stable_context is None:
                self.remove_recovered_directory(directory, transaction_fd, info, data)
                return
            holding_name, holding, holding_fd, holding_info, transaction_info = stable_context
            self.cleanup_existing_stable_holding(
                holding_name,
                holding,
                holding_fd,
                holding_info,
                transaction_fd if transaction_info is not None else None,
                transaction_info,
                data,
            )

        services_info = os.fstat(self.services_fd)
        if (
            services_info.st_dev != data["servicesDevice"]
            or services_info.st_ino != data["servicesInode"]
        ):
            retain_manual("published target parent differs from transaction journal")

        target_name = f"{data['name']}-service"
        try:
            target_info = os.stat(
                target_name, dir_fd=self.services_fd, follow_symlinks=False,
            )
            if (
                stat.S_ISDIR(target_info.st_mode)
                and target_info.st_dev == data["stagedDevice"]
                and target_info.st_ino == data["stagedInode"]
            ):
                target_state = "owned"
            else:
                target_state = "external"
        except FileNotFoundError:
            target_info = None
            target_state = "absent"

        pom_state = metadata_state("pom", self.root_pom)
        catalog_state = metadata_state("catalog", self.catalog)
        state_pair = (pom_state, catalog_state)

        def finish_committed() -> None:
            if target_info is None:
                retain_manual("committed target is missing")
            os.fsync(self.services_fd)
            try:
                after_target = os.stat(
                    target_name, dir_fd=self.services_fd, follow_symlinks=False,
                )
            except FileNotFoundError:
                retain_manual("committed target disappeared during durability recovery")
            if not same_inode(target_info, after_target):
                retain_manual("committed target changed during durability recovery")
            if stage != "COMMITTED":
                data["stage"] = "COMMITTED"
                data["recoveryErrors"] = []
                self.write_existing_journal(
                    evidence_directory,
                    data,
                    evidence_fd,
                    evidence_parent_fd,
                )

        matching = (
            data["name"] == self.name
            and data["port"] == self.port
            and data["schema"] == self.schema
        )
        if stage == "COMMITTED":
            if target_state != "owned" or state_pair != ("intended", "intended"):
                retain_manual("COMMITTED state conflicts with target or intended metadata")
            finish_committed()
            finalize()
            return matching

        if stage == "PUBLISHING" and target_state == "owned":
            if state_pair != ("intended", "intended"):
                retain_manual("PUBLISHING target is visible but metadata is not intended")
            finish_committed()
            finalize()
            return matching

        if target_state != "absent":
            retain_manual(f"{stage} state contains an external or future target")

        allowed_states = {
            "PREPARING": {("original", "original")},
            "PREPARED": {("original", "original")},
            "POM_EXCHANGED": {("intended", "original"), ("original", "original")},
            "CATALOG_EXCHANGED": {
                ("intended", "intended"),
                ("intended", "original"),
                ("original", "original"),
            },
            "PUBLISHING": {
                ("intended", "intended"),
                ("intended", "original"),
                ("original", "original"),
            },
        }
        if state_pair not in allowed_states[stage]:
            retain_manual(f"{stage} metadata nodes are inconsistent or externally changed")
        if stage == "PREPARING" and (
            data["pomIntendedSha256"] is not None
            or data["catalogIntendedSha256"] is not None
        ):
            retain_manual("PREPARING contains future intended metadata identities")
        if stage != "PREPARING" and (
            data["pomIntendedSha256"] is None
            or data["catalogIntendedSha256"] is None
        ):
            retain_manual(f"{stage} lacks intended metadata identities")

        errors: list[str] = []
        if catalog_state == "intended":
            catalog_error = self.recover_file(
                directory, transaction_fd, data, "catalog", self.catalog,
            )
            if catalog_error:
                errors.append(catalog_error)
        if pom_state == "intended":
            pom_error = self.recover_file(
                directory, transaction_fd, data, "pom", self.root_pom,
            )
            if pom_error:
                errors.append(pom_error)
        if errors:
            retain_manual("; ".join(errors))
        if (
            metadata_state("pom", self.root_pom) != "original"
            or metadata_state("catalog", self.catalog) != "original"
        ):
            retain_manual(f"{stage} rollback verification failed")
        finalize()
        return False

    def recover_stable_holding_children(self) -> bool:
        if self.stable_holding_fd is None:
            fail("stable holding root descriptor unavailable before recovery")
        recovered_matching = False
        for holding_name in sorted(os.listdir(self.stable_holding_fd)):
            holding = self.stable_holding_root / holding_name
            info = os.stat(
                holding_name,
                dir_fd=self.stable_holding_fd,
                follow_symlinks=False,
            )
            if (
                not holding_name.startswith(".create-service.cleanup-")
                or not stat.S_ISDIR(info.st_mode)
            ):
                fail(f"unexpected stable holding artifact: {holding}")
            holding_fd = os.open(
                holding_name,
                os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                dir_fd=self.stable_holding_fd,
            )
            transaction_fd: Optional[int] = None
            try:
                opened = os.fstat(holding_fd)
                visible = os.stat(
                    holding_name,
                    dir_fd=self.stable_holding_fd,
                    follow_symlinks=False,
                )
                if not same_inode(opened, visible) or not same_inode(opened, info):
                    fail(f"stable holding child changed while opening: {holding}")
                data = self.load_pending_journal(holding, holding_fd)
                entries = sorted(name for name in os.listdir(holding_fd) if name != "RECOVERY.json")
                if not entries:
                    if data.get("holdingState") == "PREPARING":
                        self.cleanup_existing_stable_holding(
                            holding_name, holding, holding_fd, opened, None, None, data,
                        )
                        continue
                    if str(data["stage"]) not in {"COMMITTED", "PUBLISHING"}:
                        fail(
                            f"stable holding child lacks its transaction; journal retained at "
                            f"{holding / 'RECOVERY.json'}"
                        )
                    recovered_matching = self.recover_transaction_state(
                        holding,
                        holding_fd,
                        opened,
                        data,
                        holding,
                        holding_fd,
                        self.stable_holding_fd,
                        (holding_name, holding, holding_fd, opened, None),
                    ) or recovered_matching
                    continue
                if entries != ["transaction"]:
                    fail(f"unexpected entries in stable holding child: {holding}")
                transaction_fd = os.open(
                    "transaction",
                    os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                    dir_fd=holding_fd,
                )
                transaction_info = os.fstat(transaction_fd)
                transaction_visible = os.stat(
                    "transaction", dir_fd=holding_fd, follow_symlinks=False,
                )
                if not same_inode(transaction_info, transaction_visible):
                    fail(f"stable holding transaction changed while opening: {holding}")
                transaction = holding / "transaction"
                try:
                    nested_journal = os.stat(
                        "RECOVERY.json", dir_fd=transaction_fd, follow_symlinks=False,
                    )
                except FileNotFoundError:
                    nested_journal = None
                if nested_journal is None:
                    if data.get("holdingState") == "PREPARING":
                        fail(
                            f"stable holding PREPARING transaction lacks its journal at "
                            f"{transaction}"
                        )
                    # The wrapper journal is written and fsynced before nested
                    # journal-last cleanup begins, so it remains authoritative here.
                    nested_data = data
                else:
                    nested_data = self.load_pending_journal(transaction, transaction_fd)
                if data.get("holdingState") == "PREPARING":
                    if data["ownerToken"] != nested_data["ownerToken"]:
                        fail(f"stable holding PREPARING owner mismatch at {holding}")
                    data = nested_data
                    self.write_stable_holding_journal(holding_fd, data)
                elif data != nested_data:
                    fail(f"stable holding and nested recovery journals differ at {holding}")
                recovered_matching = self.recover_transaction_state(
                    transaction,
                    transaction_fd,
                    transaction_info,
                    data,
                    holding,
                    holding_fd,
                    self.stable_holding_fd,
                    (holding_name, holding, holding_fd, opened, transaction_info),
                ) or recovered_matching
            finally:
                if transaction_fd is not None:
                    os.close(transaction_fd)
                os.close(holding_fd)
        return recovered_matching

    def recover_pending_transactions(self) -> bool:
        if self.lock_fd is None or self.services_fd is None:
            fail("bound repository descriptors unavailable before recovery")
        recovered_matching = self.recover_stable_holding_children()
        for directory in sorted(self.repo_root.glob(".create-service.*")):
            if directory.name == ".create-service.lock":
                continue
            if directory == self.transaction_dir:
                continue
            try:
                info = directory.lstat()
            except FileNotFoundError:
                continue
            if not stat.S_ISDIR(info.st_mode):
                fail(f"unexpected service generation recovery artifact: {directory}")
            transaction_fd = os.open(
                directory.name,
                os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY,
                dir_fd=self.lock_fd,
            )
            try:
                opened = os.fstat(transaction_fd)
                visible = os.stat(
                    directory.name, dir_fd=self.lock_fd, follow_symlinks=False,
                )
                if not same_inode(opened, visible) or not same_inode(opened, info):
                    fail(f"recovery transaction ownership changed at {directory}")
                data = self.load_pending_journal(directory, transaction_fd)
                recovered_matching = self.recover_transaction_state(
                    directory,
                    transaction_fd,
                    opened,
                    data,
                    directory,
                    transaction_fd,
                    self.lock_fd,
                ) or recovered_matching
            finally:
                os.close(transaction_fd)
        return recovered_matching

    def execute(self) -> None:
        original_error: Optional[Exception] = None
        succeeded = False
        try:
            self.acquire_lock()
            try:
                self.verify_transaction_filesystem()
                self.prepare_stable_holding_root()
                self.probe_native_filesystem()
                self.recovered_matching_commit = self.recover_pending_transactions()
                if self.recovered_matching_commit:
                    succeeded = True
                else:
                    self.snapshot()
                    self.validate_template()
                    if self.target.exists() or self.target.is_symlink():
                        fail(f"target service already exists: {self.target_relative.as_posix()}")
                    staged, next_pom, next_catalog = self.create_staging()
                    render_service(self.template, staged, self.name, self.port)
                    build_metadata(
                        self.pom_snapshot, self.catalog_snapshot, next_pom, next_catalog,
                        self.name, self.port, self.schema,
                    )
                    os.chmod(next_pom, self.pom_mode)
                    os.chmod(next_catalog, self.catalog_mode)
                    self.prepare_journal(staged, next_pom, next_catalog)
                    self.commit(staged, next_pom, next_catalog)
                    succeeded = True
            except Exception as error:
                ignore_transaction_signals()
                if self.committed:
                    succeeded = True
                    if isinstance(error, ControlledInterruption):
                        self.post_commit_notice = (
                            "service generation was already committed before signal "
                            f"{error.signum}; completing cleanup without rollback"
                        )
                    else:
                        self.post_commit_notice = (
                            "service generation was already committed before post-commit error: "
                            f"{error}; completing cleanup without rollback"
                        )
                else:
                    original_error = error
                    self.rollback()

            if succeeded:
                self.clean_staging()
                if self.recovery_errors:
                    original_error = GenerationError(
                        "generation committed but transaction cleanup was incomplete"
                    )
                    succeeded = False
            elif original_error is not None and not self.recovery_errors:
                self.clean_staging()

            if not succeeded:
                if original_error is None:
                    original_error = GenerationError("service generation failed")
                print(str(original_error), file=sys.stderr)
                if self.recovery_errors:
                    journal = self.write_journal(original_error)
                    location = journal if journal is not None else self.transaction_dir
                    print(f"recovery incomplete; journal retained at {location}", file=sys.stderr)
                    for error in self.recovery_errors:
                        print(f"recovery error: {error}", file=sys.stderr)
                elif self.rollback_completed or self.rollback_notes:
                    if self.rollback_notes:
                        print(
                            "rollback completed after retry: " + "; ".join(self.rollback_notes),
                            file=sys.stderr,
                        )
                    else:
                        print("rollback completed", file=sys.stderr)
                raise SystemExit(1)
        finally:
            ignore_transaction_signals()
            self.release_lock()

        if self.post_commit_notice:
            print(self.post_commit_notice, file=sys.stderr)
        print(f"created {self.target_relative.as_posix()} with port {self.port} and schema {self.schema}")


def install_signal_handlers() -> None:
    def interrupt(signum: int, _frame: object) -> None:
        raise ControlledInterruption(signum)

    for signum in TRANSACTION_SIGNALS:
        signal.signal(signum, interrupt)


def main() -> None:
    if len(sys.argv) < 2:
        fail("internal error: repository root argument is missing")
    repo_root = Path(sys.argv[1])
    name, port, schema = parse_arguments(sys.argv[2:])
    if not hasattr(signal, "pthread_sigmask"):
        fail("POSIX pthread_sigmask support is required by create-service.sh")
    install_signal_handlers()
    Transaction(repo_root, name, port, schema).execute()


try:
    main()
except GenerationError as error:
    print(str(error), file=sys.stderr)
    raise SystemExit(1)
PY
