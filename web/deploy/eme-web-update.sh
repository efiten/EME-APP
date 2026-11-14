#!/usr/bin/env bash
# Installs an eme-web release (eme-web-<tag>.zip) on this host. Runs as root.
# Install as /usr/local/sbin/eme-web-update (mode 755). It is the only install path:
# the systemd timer runs it without argument (latest release), deploy-release.sh runs it with a tag.
# Usage: eme-web-update [vX.Y.Z]
# Needs: curl, unzip, python3, flock (util-linux), systemd.
set -Eeuo pipefail

REPO=on5gsdirk-cmd/EME-APP
WEB_DIR=/var/www/eme.on8ar.eu
BIN_DIR=/opt/eme-web
ENV_FILE=/etc/default/eme-web
STATE_DIR=/var/lib/eme-web
FAILED_FILE=$STATE_DIR/failed-version
MARKER=$STATE_DIR/in-progress
LOCK_FILE=/run/eme-web-update.lock
LOCAL_URL=http://127.0.0.1:5200/api/version
TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'

mark_failed() {
  mkdir -p "$STATE_DIR"
  printf '%s\n' "$1" > "$FAILED_FILE"
}

# Succeeds when the local server reports version $1 within 30 s.
wait_version() {
  local body
  for _ in $(seq 1 30); do
    if body=$(curl -fsS --max-time 3 "$LOCAL_URL" 2>/dev/null) && [[ $body == *"\"version\":\"$1\""* ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

read_env_version() {
  if [ -f "$ENV_FILE" ]; then sed -n '/^EME_VERSION=/{s///p;q}' "$ENV_FILE"; fi
  return 0
}

# Puts the .prev copies back. $1 is the version that failed. It is marked failed first, so the timer
# does not retry it even if the rollback itself stops half way. Returns 0 only when the restored
# version answers.
do_rollback() {
  trap - ERR
  set +e
  local failed=$1 restored
  mark_failed "$failed"
  if [ -d "$WEB_DIR.prev" ] && [ -f "$BIN_DIR/eme-web.prev" ] && [ -f "$ENV_FILE.prev" ]; then
    echo "rolling back $failed" >&2
    rm -rf "$WEB_DIR.failed" "$BIN_DIR/eme-web.failed"
    if [ -d "$WEB_DIR" ]; then mv "$WEB_DIR" "$WEB_DIR.failed"; fi
    mv "$WEB_DIR.prev" "$WEB_DIR"
    if [ -f "$BIN_DIR/eme-web" ]; then mv "$BIN_DIR/eme-web" "$BIN_DIR/eme-web.failed"; fi
    mv "$BIN_DIR/eme-web.prev" "$BIN_DIR/eme-web"
    mv "$ENV_FILE.prev" "$ENV_FILE"
    rm -f "$MARKER"
    systemctl daemon-reload
    systemctl restart eme-web
    restored=$(read_env_version)
    if wait_version "$restored"; then
      echo "rolled back to ${restored:-unknown}, it answers on $LOCAL_URL. $failed is marked failed. Failed files: $WEB_DIR.failed, $BIN_DIR/eme-web.failed" >&2
      return 0
    fi
    echo "rolled back to ${restored:-unknown} but it does NOT answer on $LOCAL_URL within 30 s. $failed is marked failed. Check 'systemctl status eme-web'" >&2
    return 1
  fi
  rm -f "$MARKER"
  echo "ROLLBACK INCOMPLETE: not all .prev copies exist, nothing was restored (no mixing of versions). $failed is marked failed." >&2
  echo "Files of $failed still in place: $WEB_DIR, $BIN_DIR/eme-web, $ENV_FILE. Present .prev copies:" >&2
  ls -d "$WEB_DIR.prev" "$BIN_DIR/eme-web.prev" "$ENV_FILE.prev" 2>/dev/null >&2
  return 1
}

rollback() {
  do_rollback "$version" || true
  exit 1
}

tag=${1:-}
if [ "$#" -gt 1 ] || { [ -n "$tag" ] && ! [[ "$tag" =~ $TAG_RE ]]; }; then
  echo "Usage: $0 [vX.Y.Z] (got '$*')" >&2
  exit 2
fi
explicit=0
[ -n "$tag" ] && explicit=1

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  if [ "$explicit" = 1 ]; then
    echo "another eme-web-update is running, try again later" >&2
    exit 75
  fi
  echo "another eme-web-update is running, nothing to do"
  exit 0
fi

# A marker means an earlier run died between the swap and a successful check.
if [ -f "$MARKER" ]; then
  version=$(<"$MARKER")
  echo "found an unfinished deploy of ${version:-unknown}, rolling back first" >&2
  do_rollback "${version:-unknown}" || exit 1
  set -e
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

current=$(read_env_version)
if [ "$current" = "$version" ]; then
  echo "already at $version"
  exit 0
fi

failed_mark=
if [ -f "$FAILED_FILE" ]; then read -r failed_mark < "$FAILED_FILE" || true; fi
if [ "$failed_mark" = "$version" ]; then
  if [ "$explicit" = 1 ]; then
    rm -f "$FAILED_FILE"
  else
    echo "$version failed to deploy before and was rolled back; not retrying. Run 'eme-web-update $tag' to try again"
    exit 0
  fi
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "downloading eme-web-$tag.zip (current: ${current:-none})"
code=$(curl -sSL --max-time 120 -o "$tmp/eme-web.zip" -w '%{http_code}' \
  "https://github.com/$REPO/releases/download/$tag/eme-web-$tag.zip") || {
  echo "download failed (network error), not marking $version failed" >&2
  exit 1
}
if [ "$code" = 404 ]; then
  echo "eme-web-$tag.zip does not exist (HTTP 404)" >&2
  # Only a timer run saw the asset in the API listing. An explicit run can come before
  # web-upload has finished, so it must not mark the version failed.
  [ "$explicit" = 1 ] || mark_failed "$version"
  exit 1
elif [ "$code" != 200 ]; then
  echo "download returned HTTP $code, not marking $version failed" >&2
  exit 1
fi

python3 -I - "$tmp/eme-web.zip" <<'PY' || { mark_failed "$version"; exit 1; }
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
unzip -q "$tmp/eme-web.zip" -d "$tmp/pack" || { mark_failed "$version"; exit 1; }
if [ ! -f "$tmp/pack/dist/index.html" ] || [ ! -f "$tmp/pack/eme-web" ]; then
  echo "dist/index.html or eme-web missing in the zip" >&2
  mark_failed "$version"
  exit 1
fi

for f in "$WEB_DIR/index.html" "$BIN_DIR/eme-web" "$ENV_FILE"; do
  [ -f "$f" ] || { echo "$f is missing, install the first version by hand. Nothing was changed." >&2; exit 1; }
done

echo "staging $version"
rm -rf "$WEB_DIR.new" "$BIN_DIR/eme-web.new" "$WEB_DIR.old"
cp -r "$tmp/pack/dist" "$WEB_DIR.new"
cp "$tmp/pack/eme-web" "$BIN_DIR/eme-web.new"
chown -R root:root "$WEB_DIR.new" "$BIN_DIR/eme-web.new"
find "$WEB_DIR.new" -type d -exec chmod 755 {} +
find "$WEB_DIR.new" -type f -exec chmod 644 {} +
chmod 755 "$BIN_DIR/eme-web.new"

# From the marker on, a failure rolls back (ERR trap and explicit calls) until the check succeeds.
echo "saving the running version as .prev"
rm -rf "$WEB_DIR.prev" "$BIN_DIR/eme-web.prev" "$ENV_FILE.prev"
cp -a "$WEB_DIR" "$WEB_DIR.prev"
cp -p "$BIN_DIR/eme-web" "$BIN_DIR/eme-web.prev"
cp -p "$ENV_FILE" "$ENV_FILE.prev"
mkdir -p "$STATE_DIR"
printf '%s\n' "$version" > "$MARKER"
trap rollback ERR

echo "swapping files"
mv "$WEB_DIR" "$WEB_DIR.old"
mv "$WEB_DIR.new" "$WEB_DIR"
mv "$BIN_DIR/eme-web.new" "$BIN_DIR/eme-web"
printf 'EME_VERSION=%s\n' "$version" > "$ENV_FILE"
systemctl daemon-reload
systemctl restart eme-web

echo "waiting for $LOCAL_URL to report $version"
if ! wait_version "$version"; then
  echo "FAILED: $LOCAL_URL did not report $version within 30 s" >&2
  rollback
fi

trap - ERR
rm -f "$MARKER"
rm -rf "$WEB_DIR.old"
if [ -f "$FAILED_FILE" ]; then
  read -r failed_mark < "$FAILED_FILE" || true
  if [ "$failed_mark" = "$version" ]; then rm -f "$FAILED_FILE"; fi
fi
echo "deployed $version"
