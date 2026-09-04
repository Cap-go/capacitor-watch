#!/usr/bin/env bash
set -euo pipefail

# Prefer the newest available iPhone simulator on the runner.
python3 - <<'PY'
import json
import subprocess
import sys

raw = subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"], text=True)
devices_by_runtime = json.loads(raw)["devices"]

def runtime_sort_key(runtime: str) -> tuple:
    parts = []
    for part in runtime.replace("com.apple.CoreSimulator.SimRuntime.", "").split("-"):
        try:
            parts.append(int(part))
        except ValueError:
            parts.append(part)
    return tuple(parts)

for runtime in sorted(devices_by_runtime.keys(), key=runtime_sort_key, reverse=True):
    if "iOS" not in runtime and "iphoneos" not in runtime.lower():
        continue
    iphones = [
        device
        for device in devices_by_runtime[runtime]
        if device.get("isAvailable") and "iPhone" in device.get("name", "")
    ]
    if not iphones:
        continue
    iphones.sort(key=lambda device: device.get("name", ""))
    device = iphones[0]
    print(f"platform=iOS Simulator,id={device['udid']}")
    sys.exit(0)

sys.stderr.write("No available iPhone simulator found\n")
sys.exit(1)
PY
