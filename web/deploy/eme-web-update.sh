#!/usr/bin/env bash
# Installs an eme-web release (eme-web-<tag>.zip) on this host. Runs as root.
# Install as /usr/local/sbin/eme-web-update (mode 755). It is the only install path:
# the systemd timer runs it without argument (latest release), deploy-release.sh runs it with a tag.
# Usage: eme-web-update [vX.Y.Z]
set -euo pipefail

REPO=on5gsdirk-cmd/EME-APP
WEB_DIR=/var/www/eme.on8ar.eu
BIN_DIR=/opt/eme-web
ENV_FILE=/etc/default/eme-web
STATE_DIR=/var/lib/eme-web
FAILED_FILE=$STATE_DIR/failed-version
LOCK_FILE=/run/eme-web-update.lock
LOCAL_URL=http://127.0.0.1:5200/api/version
TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'

tag=${1:-}
if [ "$#" -gt 1 ] || { [ -n "$tag" ] && ! [[ "$tag" =~ $TAG_RE ]]; }; then
  echo "Usage: $0 [vX.Y.Z] (got '$*')" >&2
  exit 2
fi
explicit=0
[ -n "$tag" ] && explicit=1

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "another eme-web-update is running, nothing to do"
  exit 0
fi

if [ "$explicit" = 0 ]; then
  release_json=$(curl -fsS --max-time 30 \
    -H 'Accept: application/vnd.github+json' -H 'User-Agent: EME-ON8AR-Web-Updater' \
    "https://api.github.com/repos/$REPO/releases/latest")
  parsed=$(printf '%s' "$release_json" | python3 -I -c '
import json, sys
r = json.load(sys.stdin)
tag = r["tag_name"]
names = [a.get("name", "") for a in r.get("assets", [])]
print(tag)
print("yes" if "eme-web-%s.zip" % tag in names else "no")
')
  tag=$(printf '%s\n' "$parsed" | sed -n 1p)
  has_asset=$(printf '%s\n' "$parsed" | sed -n 2p)
  if ! [[ "$tag" =~ $TAG_RE ]]; then
    echo "latest release has an unexpected tag '$tag', nothing done" >&2
    exit 1
  fi
  if [ "$has_asset" != yes ]; then
    echo "latest release $tag has no eme-web-$tag.zip, nothing to do"
    exit 0
  fi
fi
version=${tag#v}

current=
if [ -f "$ENV_FILE" ]; then
  current=$(sed -n 's/^EME_VERSION=//p' "$ENV_FILE" | head -n 1)
fi
if [ "$current" = "$version" ]; then
  echo "already at $version"
  exit 0
fi

if [ "$explicit" = 1 ]; then
  rm -f "$FAILED_FILE"
elif [ -f "$FAILED_FILE" ] && [ "$(head -n 1 "$FAILED_FILE")" = "$version" ]; then
  echo "$version failed to deploy before and was rolled back; not retrying. Run 'eme-web-update $tag' to try again"
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "downloading eme-web-$tag.zip (current: ${current:-none})"
curl -fsSL --max-time 120 -o "$tmp/eme-web.zip" \
  "https://github.com/$REPO/releases/download/$tag/eme-web-$tag.zip"

python3 -I - "$tmp/eme-web.zip" <<'PY'
import stat, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for i in z.infolist():
        n = i.filename
        parts = n.split("/")
        if n.startswith("/") or "\\" in n or ".." in parts:
            sys.exit("unsafe path in zip: %r" % n)
        if stat.S_ISLNK(i.external_attr >> 16):
            sys.exit("symlink in zip: %r" % n)
        if n != "eme-web" and not n.startswith("dist/"):
            sys.exit("unexpected entry in zip: %r" % n)
PY

mkdir "$tmp/pack"
unzip -q "$tmp/eme-web.zip" -d "$tmp/pack"
[ -f "$tmp/pack/dist/index.html" ] || { echo "dist/index.html missing in the zip" >&2; exit 1; }
[ -f "$tmp/pack/eme-web" ] || { echo "eme-web missing in the zip" >&2; exit 1; }

echo "staging $version"
rm -rf "$WEB_DIR.new" "$BIN_DIR/eme-web.new"
mkdir -p "$BIN_DIR"
cp -r "$tmp/pack/dist" "$WEB_DIR.new"
cp "$tmp/pack/eme-web" "$BIN_DIR/eme-web.new"
chown -R root:root "$WEB_DIR.new" "$BIN_DIR/eme-web.new"
find "$WEB_DIR.new" -type d -exec chmod 755 {} +
find "$WEB_DIR.new" -type f -exec chmod 644 {} +
chmod 755 "$BIN_DIR/eme-web.new"

echo "swapping files, previous version kept as .prev"
rm -rf "$WEB_DIR.prev" "$BIN_DIR/eme-web.prev" "$ENV_FILE.prev"
if [ -d "$WEB_DIR" ]; then mv "$WEB_DIR" "$WEB_DIR.prev"; fi
mv "$WEB_DIR.new" "$WEB_DIR"
if [ -f "$BIN_DIR/eme-web" ]; then mv "$BIN_DIR/eme-web" "$BIN_DIR/eme-web.prev"; fi
mv "$BIN_DIR/eme-web.new" "$BIN_DIR/eme-web"
if [ -f "$ENV_FILE" ]; then cp -p "$ENV_FILE" "$ENV_FILE.prev"; fi
printf 'EME_VERSION=%s\n' "$version" > "$ENV_FILE"
systemctl daemon-reload
systemctl restart eme-web

echo "waiting for $LOCAL_URL to report $version"
ok=0
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "$LOCAL_URL" 2>/dev/null | grep -qF "\"version\":\"$version\""; then
    ok=1
    break
  fi
  sleep 1
done

if [ "$ok" = 1 ]; then
  rm -f "$FAILED_FILE"
  echo "deployed $version"
  exit 0
fi

echo "FAILED: $LOCAL_URL did not report $version within 30 s, rolling back to ${current:-previous}" >&2
rm -rf "$WEB_DIR.failed" "$BIN_DIR/eme-web.failed"
if [ -d "$WEB_DIR.prev" ]; then mv "$WEB_DIR" "$WEB_DIR.failed"; mv "$WEB_DIR.prev" "$WEB_DIR"; fi
if [ -f "$BIN_DIR/eme-web.prev" ]; then mv "$BIN_DIR/eme-web" "$BIN_DIR/eme-web.failed"; mv "$BIN_DIR/eme-web.prev" "$BIN_DIR/eme-web"; fi
if [ -f "$ENV_FILE.prev" ]; then mv "$ENV_FILE.prev" "$ENV_FILE"; fi
systemctl daemon-reload
systemctl restart eme-web
mkdir -p "$STATE_DIR"
printf '%s\n' "$version" > "$FAILED_FILE"
echo "rolled back; $version is marked failed and the timer will not retry it. Failed files: $WEB_DIR.failed, $BIN_DIR/eme-web.failed" >&2
exit 1
