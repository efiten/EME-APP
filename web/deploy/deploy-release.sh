#!/usr/bin/env bash
# Deploys a published release (eme-web-<tag>.zip) to eme.on8ar.eu.
# Usage: EME_DEPLOY_HOST=root@host EME_DEPLOY_KEY=/path/to/key deploy-release.sh vX.Y.Z
# The systemd unit and the nginx site are installed once by hand (see web/deploy/); this script
# stops when the unit is missing, and otherwise only replaces the web files and the server binary, keeping the previous ones as .prev.
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

echo "3/6 Checking that the eme-web unit is installed on $EME_DEPLOY_HOST"
if ! ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" "systemctl cat eme-web >/dev/null 2>&1"; then
  echo "systemd unit eme-web is not installed on $EME_DEPLOY_HOST. Install web/deploy/eme-web.service first (task 14). Nothing was changed." >&2
  exit 1
fi

echo "4/6 Copying to $EME_DEPLOY_HOST"
ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" "rm -rf '$WEB_DIR.new' '$BIN_DIR/eme-web.new' && mkdir -p '$WEB_DIR.new' '$BIN_DIR'"
scp "${ssh_opts[@]}" -q -r "$tmp/pack/dist/." "$EME_DEPLOY_HOST:$WEB_DIR.new/"
scp "${ssh_opts[@]}" -q "$tmp/pack/eme-web" "$EME_DEPLOY_HOST:$BIN_DIR/eme-web.new"

echo "5/6 Swapping files, writing /etc/default/eme-web, restarting (previous version kept as .prev)"
ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" bash -s -- "$WEB_DIR" "$BIN_DIR" "$version" <<'REMOTE'
set -euo pipefail
web=$1; bin=$2; version=$3
env_file=/etc/default/eme-web
chmod 755 "$bin/eme-web.new"
chown -R root:root "$web.new" "$bin/eme-web.new"
find "$web.new" -type d -exec chmod 755 {} +
find "$web.new" -type f -exec chmod 644 {} +
rm -rf "$web.prev" "$bin/eme-web.prev" "$env_file.prev"
if [ -d "$web" ]; then mv "$web" "$web.prev"; fi
mv "$web.new" "$web"
if [ -f "$bin/eme-web" ]; then mv "$bin/eme-web" "$bin/eme-web.prev"; fi
mv "$bin/eme-web.new" "$bin/eme-web"
if [ -f "$env_file" ]; then cp -p "$env_file" "$env_file.prev"; fi
printf 'EME_VERSION=%s\n' "$version" > "$env_file"
systemctl daemon-reload
systemctl restart eme-web
REMOTE

echo "6/6 Waiting for $URL_VERSION to report $version"
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "$URL_VERSION" 2>/dev/null | grep -qF "\"version\":\"$version\""; then
    echo "OK: eme.on8ar.eu runs $version"
    exit 0
  fi
  sleep 1
done
echo "FAILED: $URL_VERSION did not report $version within 30 s." >&2
echo "Rollback, run on $EME_DEPLOY_HOST (only the parts that have a .prev):" >&2
cat >&2 <<ROLLBACK
  rm -rf $WEB_DIR.failed $BIN_DIR/eme-web.failed
  mv $WEB_DIR $WEB_DIR.failed && mv $WEB_DIR.prev $WEB_DIR
  mv $BIN_DIR/eme-web $BIN_DIR/eme-web.failed && mv $BIN_DIR/eme-web.prev $BIN_DIR/eme-web
  mv /etc/default/eme-web.prev /etc/default/eme-web
  systemctl restart eme-web
ROLLBACK
exit 1
