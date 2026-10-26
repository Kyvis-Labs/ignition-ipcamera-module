#!/usr/bin/env bash
# Builds every variant into build/dist/: Camera.modl (external MediaMTX) and one module per
# platform with MediaMTX inside. Variants differ only in the gateway jar's bundled binary.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"
dist="$here/build/dist"
rm -rf "$dist"
mkdir -p "$dist"

for platform in "" linux-x64 linux-arm64 windows-x64 macos-arm64; do
  name="Camera${platform:+-$platform}"
  echo "== $name"
  ./gradlew build --console=plain -q ${platform:+-Pplatform=$platform}
  cp "build/$name.unsigned.modl" "$dist/"
done
ls -lh "$dist"
