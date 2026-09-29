"""Check aapt dump permissions output, including merged library permissions."""
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text()
permissions = set(re.findall(r"uses-permission[^:]*: name='([^']+)'", text))
allowed = {"dev.friction.spike.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
unexpected = permissions - allowed
if unexpected:
    raise SystemExit(f"Unexpected packaged permissions: {sorted(unexpected)}")
if "package: dev.friction.spike" not in text:
    raise SystemExit("Expected Friction package missing; inspect aapt input")
print(f"Packaged permission check passed: {sorted(permissions)}")
