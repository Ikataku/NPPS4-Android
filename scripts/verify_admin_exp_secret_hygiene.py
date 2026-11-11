#!/usr/bin/env python3
"""Fail the Android build if admin EXP credentials can be embedded in APK metadata.

The values in envvars.txt are never printed. Only key names are inspected.
"""
from pathlib import Path
import sys

APP = Path("app/src/main/java/com/npdep/npps4/App.java")
MANIFEST = Path("app/src/main/AndroidManifest.xml")
ENVVARS = Path("envvars.txt")


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


source = APP.read_text(encoding="utf-8")
guard = 'envKey.startsWith("NPPS4_ADMIN_EXP_")'
if guard not in source or "Admin EXP secrets must not be stored in manifest metadata" not in source:
    fail("App.java must reject NPPS4_ADMIN_EXP_* manifest metadata")

for line_number, line in enumerate(source.splitlines(), start=1):
    if "Log.d(" in line and "envValue" in line:
        fail(f"App.java line {line_number} logs an environment value")
if '(value redacted)' not in source:
    fail("App.java must use an explicit redacted environment log")

manifest = MANIFEST.read_text(encoding="utf-8")
if "env.NPPS4_ADMIN_EXP_" in manifest:
    fail("AndroidManifest.xml contains an admin EXP environment metadata key")

if not ENVVARS.is_file():
    fail("envvars.txt was not generated before the secret-hygiene check")

for line_number, raw_line in enumerate(ENVVARS.read_text(encoding="utf-8").splitlines(), start=1):
    stripped = raw_line.strip()
    if not stripped or stripped.startswith("#"):
        continue
    key = stripped.split("=", 1)[0].strip()
    if key.startswith("NPPS4_ADMIN_EXP_"):
        fail(f"envvars.txt line {line_number} contains forbidden key {key}")

print("PASS: admin EXP secrets are not embedded in manifest metadata")
print("PASS: environment values are redacted from App log output")
print("PASS: envvars.txt contains no NPPS4_ADMIN_EXP_* keys")
