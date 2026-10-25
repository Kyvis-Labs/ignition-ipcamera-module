#!/usr/bin/env bash
# Build the module and load it on a dev gateway.
#
# Unsigned modules cannot go through the gateway REST API (it quarantines them
# with no way out), so this copies into the stack's externalModulesFolder and
# restarts the container -- there is no hot-swap for unsigned modules.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
stack="${IGNITION_STACK:?set IGNITION_STACK to the gateway compose directory}"

cd "$here"
./gradlew build --console=plain -q

modl="$here/build/Camera.unsigned.modl"
rm -f "$stack"/var/modules/Camera*.modl
cp "$modl" "$stack/var/modules/"

since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker compose -f "$stack/docker-compose.yml" restart ignition >/dev/null 2>&1 \
  || docker compose -f "$stack/docker-compose.yml" up -d ignition >/dev/null

echo "waiting for the camera module to start..."
for _ in $(seq 1 90); do
  logs="$(docker compose -f "$stack/docker-compose.yml" logs --since "$since" ignition 2>&1)"
  if grep -q "Camera module started" <<<"$logs"; then
    grep -E "CameraManager|GatewayHook|EventService|kyvislabs\.camera" <<<"$logs" | tail -20
    exit 0
  fi
  if grep -qiE "Error starting up module.*(camera|Camera)|com\.kyvislabs\.camera.*(Exception|Error)" <<<"$logs"; then
    grep -iE -A15 "camera" <<<"$logs" | tail -60
    exit 1
  fi
  sleep 2
done
echo "timed out; check: docker compose -f $stack/docker-compose.yml logs ignition" >&2
exit 1
