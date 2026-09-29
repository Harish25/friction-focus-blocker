#!/usr/bin/env bash
# Keep platform-tools in the project; restore the historical /tmp path after reboot.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_dir="$project_dir/.local-tools/sdk"
mkdir -p "$sdk_dir"
if [[ ! -x "$sdk_dir/platform-tools/adb" ]]; then
    archive_path="$(mktemp /tmp/friction-platform-tools.XXXXXX.zip)"
    trap 'rm -f "$archive_path"' EXIT
    curl --fail --location --retry 3 \
        https://dl.google.com/android/repository/platform-tools-latest-linux.zip \
        --output "$archive_path"
    unzip -q -o "$archive_path" -d "$sdk_dir"
fi
mkdir -p /tmp/friction-tools/sdk
if [[ ! -e /tmp/friction-tools/sdk/platform-tools && ! -L /tmp/friction-tools/sdk/platform-tools ]]; then
    ln -s "$sdk_dir/platform-tools" /tmp/friction-tools/sdk/platform-tools
fi
"$sdk_dir/platform-tools/adb" version
