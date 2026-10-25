#!/usr/bin/env bash
# Serve the component dev harness on http://127.0.0.1:${PORT:-8765}/ with fresh tokens for
# the given cameras (default: sim1..sim4). Tokens come from the gateway's /data/camera/token
# route, using the API token in API_TOKEN_FILE, and last 8 hours.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="${HARNESS_DIR:-$here/web/build/harness}"
gw="${GATEWAY:-https://gateway.example.com}"
api_token="$(cat "${API_TOKEN_FILE:?set API_TOKEN_FILE to a file holding the gateway API token}")"
cams=("${@:-sim1 sim2 sim3 sim4}")
read -r -a cams <<<"${cams[*]}"

mkdir -p "$out"
(cd "$here/web" && node dev/build-harness.mjs "$out")
cat > "$out/index.html" <<'HTML'
<!doctype html><html><head><meta charset="utf-8"><title>Camera harness</title>
<style>body{margin:12px;background:#111;color:#ddd;font:13px sans-serif}</style></head>
<body><div id="root"></div><script src="harness.js"></script></body></html>
HTML

# Retries for a while: right after a gateway restart the module's routes are not up yet, and
# an empty token would only show up later as unexplained 401s in the browser.
mint() {
  local tok
  for _ in $(seq 1 30); do
    tok="$(curl -fsS -X POST -H "X-Ignition-API-Token: $api_token" -H 'Content-Type: application/json' \
      -d "$1" "$gw/data/camera/token" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' 2>/dev/null)" || true
    [ -n "$tok" ] && { echo "$tok"; return; }
    sleep 2
  done
  echo "could not mint a token for $1" >&2
  exit 1
}
{
  echo '{"base": "https://cams.example.com", "cameras": {'
  sep=""
  for c in "${cams[@]}"; do
    main="$(mint "{\"camera\":\"$c\",\"ttlSec\":28800}")"
    sub="$(mint "{\"camera\":\"$c\",\"quality\":\"sub\",\"ttlSec\":28800}")"
    pb="$(mint "{\"camera\":\"$c\",\"action\":\"playback\",\"ttlSec\":28800}")"
    [ -n "$main" ] && [ -n "$sub" ] && [ -n "$pb" ] || { echo "token minting failed for $c" >&2; exit 1; }
    printf '%s"%s": {"main": "%s", "sub": "%s", "playback": "%s"}' "$sep" "$c" "$main" "$sub" "$pb"
    sep=", "
  done
  echo '}}'
} > "$out/tokens.json"
chmod 600 "$out/tokens.json"

cat > "$out/config.html" <<'HTML'
<!doctype html><html><head><meta charset="utf-8"><title>Camera config harness</title></head>
<body style="margin:0;background:#fff"><div id="root"></div><script src="config-harness.js"></script></body></html>
HTML

echo "harness: http://127.0.0.1:${PORT:-8765}/  (wall: /?wall, config page: /config.html)"
exec python3 "$here/web/dev/serve.py" "$out" "${PORT:-8765}" "$gw" "$API_TOKEN_FILE"
