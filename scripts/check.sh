#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_build="$(mktemp -d "${TMPDIR:-/tmp}/dose-companion-checks.XXXXXX")"
trap 'rmdir "$task_build" 2>/dev/null || true' EXIT

# Temporary compiled classes are cleaned by Python, without touching project files.
python3 - "$task_root" "$task_build" <<'PY'
from pathlib import Path
import shutil
import subprocess
import sys

root, output = map(Path, sys.argv[1:])
sources = sorted((root / "core/src/main/java").rglob("*.java"))
sources += sorted((root / "core/src/test/java").rglob("*.java"))
try:
    subprocess.run(["javac", "--release", "17", "-Xlint:all", "-d", str(output),
                    *map(str, sources)], check=True)
    subprocess.run(["java", "-cp", str(output), "com.dosecompanion.core.CoreChecks"], check=True)
finally:
    shutil.rmtree(output)
PY

python3 "$task_root/scripts/check_storage.py"
