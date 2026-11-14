#!/usr/bin/env bash
# Deploys a published release (eme-web-<tag>.zip) to eme.on8ar.eu.
# Usage: EME_DEPLOY_HOST=root@host EME_DEPLOY_KEY=/path/to/key deploy-release.sh vX.Y.Z
# The systemd unit and the nginx site are installed once by hand (see web/deploy/); this script
# only replaces the web files and the server binary, keeping the previous ones as .prev.
set -euo pipefail

REPO=on5gsdirk-cmd/EME-APP
WEB_DIR=/var/www/eme.on8ar.eu
BIN_DIR=/opt/eme-web
URL_VERSION=https://eme.on8ar.eu/api/version

tag=${1:-}
if ! [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "Usage: $0 vX.Y.Z (got '$tag')" >&2
  exit 2
fi
: "${EME_DEPLOY_HOST:?set EME_DEPLOY_HOST, for example root@94.130.105.135}"
: "${EME_DEPLOY_KEY:?set EME_DEPLOY_KEY to the path of the SSH key}"
version=${tag#v}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

ssh_opts=(-i "$EME_DEPLOY_KEY" -o BatchMode=yes)

echo "1/6 Downloading eme-web-$tag.zip"
curl -fsSL -o "$tmp/eme-web.zip" "https://github.com/$REPO/releases/download/$tag/eme-web-$tag.zip"

echo "2/6 Unpacking and checking"
mkdir "$tmp/pack"
unzip -q "$tmp/eme-web.zip" -d "$tmp/pack"
[ -f "$tmp/pack/dist/index.html" ] || { echo "dist/index.html missing in the zip" >&2; exit 1; }
[ -f "$tmp/pack/eme-web" ] || { echo "eme-web missing in the zip" >&2; exit 1; }

echo "3/6 Copying to $EME_DEPLOY_HOST"
ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" "rm -rf '$WEB_DIR.new' '$BIN_DIR/eme-web.new' && mkdir -p '$WEB_DIR.new' '$BIN_DIR'"
scp "${ssh_opts[@]}" -q -r "$tmp/pack/dist/." "$EME_DEPLOY_HOST:$WEB_DIR.new/"
scp "${ssh_opts[@]}" -q "$tmp/pack/eme-web" "$EME_DEPLOY_HOST:$BIN_DIR/eme-web.new"

echo "4/6 Swapping files (previous version kept as .prev)"
ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" bash -s -- "$WEB_DIR" "$BIN_DIR" <<'REMOTE'
set -euo pipefail
web=$1; bin=$2
chmod 755 "$bin/eme-web.new"
chown -R root:root "$web.new" "$bin/eme-web.new"
find "$web.new" -type d -exec chmod 755 {} +
find "$web.new" -type f -exec chmod 644 {} +
rm -rf "$web.prev" "$bin/eme-web.prev"
if [ -d "$web" ]; then mv "$web" "$web.prev"; fi
mv "$web.new" "$web"
if [ -f "$bin/eme-web" ]; then mv "$bin/eme-web" "$bin/eme-web.prev"; fi
mv "$bin/eme-web.new" "$bin/eme-web"
REMOTE

echo "5/6 Writing /etc/default/eme-web and restarting eme-web"
ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" bash -s -- "$version" <<'REMOTE'
set -euo pipefail
printf 'EME_VERSION=%s\n' "$1" > /etc/default/eme-web
unit=/etc/systemd/system/eme-web.service
stamp=/var/lib/eme-web.unit.sha
now=$(sha256sum "$unit" | cut -d' ' -f1)
if [ "$now" != "$(cat "$stamp" 2>/dev/null || true)" ]; then
  systemctl daemon-reload
  echo "$now" > "$stamp"
fi
systemctl restart eme-web
REMOTE

echo "6/6 Waiting for $URL_VERSION to report $version"
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "$URL_VERSION" 2>/dev/null | grep -q "\"version\" *: *\"$version\""; then
    echo "OK: eme.on8ar.eu runs $version"
    exit 0
  fi
  sleep 1
done
echo "FAILED: $URL_VERSION did not report $version within 30 s." >&2
echo "Rollback: on the host, swap $WEB_DIR.prev and $BIN_DIR/eme-web.prev back, then restart eme-web." >&2
exit 1
