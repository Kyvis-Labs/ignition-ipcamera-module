#!/usr/bin/env bash
# Simulated IP cameras for development: a MediaMTX container publishing test patterns with the
# camera name and wall-clock time burned in, as RTSP. cam1-cam3 are H.264, cam4 is H.265 (so it
# exercises transcoding); each has a 720p main stream and a 360p substream.
#
#   scripts/camsim/run.sh [network] [timezone]     default: devnet, the TZ of the `camera` gateway
#                                                  container (else UTC)
#
# From a gateway on the same Docker network the streams are rtsp://camsim:8554/camN and
# rtsp://camsim:8554/camNsub. Stop with: docker rm -f camsim
set -euo pipefail
# Git Bash rewrites arguments that look like POSIX paths into Windows ones; docker must see them as is.
export MSYS_NO_PATHCONV=1
here="$(cd "$(dirname "$0")" && pwd)"
network="${1:-devnet}"
tz="${2:-$(docker exec camera sh -c 'echo ${TZ:-UTC}' 2>/dev/null || echo UTC)}"
image="bluenviron/mediamtx:1.21.2-ffmpeg"

# The image has no tzdata or fonts: take the zone file from the Ignition image and a font from
# the host, so the burned-in clock matches the gateway's and the text renders.
mkdir -p "$here/.run"
docker run --rm --entrypoint cat inductiveautomation/ignition:8.3.9 "/usr/share/zoneinfo/$tz" > "$here/.run/localtime"
font=""
for f in "/c/Windows/Fonts/arial.ttf" "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf" "/System/Library/Fonts/Supplemental/Arial.ttf"; do
  [ -f "$f" ] && { font="$f"; break; }
done
[ -n "$font" ] || { echo "no TrueType font found for the clock overlay" >&2; exit 1; }

# Docker on Windows wants C:/ paths.
win() { case "$(uname -s)" in MINGW*|MSYS*) sed -E 's#^/([a-z])/#\U\1:/#' <<<"$1" ;; *) echo "$1" ;; esac; }
docker rm -f camsim >/dev/null 2>&1 || true
docker run -d --name camsim --network "$network" --restart unless-stopped \
  --label com.kyvislabs.camera.managed-by=camera-module-sim \
  --mount "type=bind,src=$(win "$here/mediamtx.yml"),dst=/mediamtx.yml,readonly" \
  --mount "type=bind,src=$(win "$here/publish.sh"),dst=/publish.sh,readonly" \
  --mount "type=bind,src=$(win "$here/.run/localtime"),dst=/etc/localtime,readonly" \
  --mount "type=bind,src=$(win "$font"),dst=/fonts/arial.ttf,readonly" \
  "$image" >/dev/null
for _ in $(seq 1 20); do
  sleep 2
  n="$(docker logs camsim 2>&1 | grep -c 'is publishing' || true)"
  [ "$n" -ge 8 ] && { echo "camsim: 8 streams publishing on network $network (tz $tz)"; exit 0; }
done
echo "camsim: only ${n:-0} of 8 streams publishing; see: docker logs camsim" >&2
exit 1
