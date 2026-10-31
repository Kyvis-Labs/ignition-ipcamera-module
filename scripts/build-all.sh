#!/usr/bin/env bash
# Builds every variant into build/dist/: Camera.modl (external MediaMTX) and one module per
# platform with MediaMTX inside. Variants differ only in the gateway jar's bundled binary.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"
dist="$here/build/dist"
rm -rf "$dist"
mkdir -p "$dist"

# Signed with the Kyvis Labs key; SIGNING_DIR moves it, and without it the modules stay unsigned.
keys="${SIGNING_DIR:-$HOME/data/kyvis}"
signing=()
run=()
suffix=".unsigned"
if [ -f "$keys/module-signing.pfx" ]; then
  suffix=""
  signing=(
    "-Pignition.signing.keystoreFile=$keys/module-signing.pfx"
    "-Pignition.signing.certFile=$keys/module-signing.crt"
    "-Pignition.signing.certAlias=kyvis-module-signing"
  )
  # through the environment, so the password is not left on a command line; these names have dots,
  # which only env can set
  pw="$(cat "$keys/module-signing.password")"
  run=(env "ORG_GRADLE_PROJECT_ignition.signing.keystorePassword=$pw" "ORG_GRADLE_PROJECT_ignition.signing.certPassword=$pw")
fi

for platform in "" linux-x64 linux-arm64 windows-x64 macos-arm64; do
  name="Camera${platform:+-$platform}"
  echo "== $name"
  "${run[@]}" ./gradlew build --console=plain -q ${platform:+-Pplatform=$platform} "${signing[@]}"
  cp "build/$name$suffix.modl" "$dist/"
done
ls -lh "$dist"
