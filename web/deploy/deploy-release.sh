#!/usr/bin/env bash
# Deploys a published release (eme-web-<tag>.zip) to eme.on8ar.eu at once, by running
# /usr/local/sbin/eme-web-update <tag> on the host (installed from web/deploy/eme-web-update.sh).
# Usage: EME_DEPLOY_HOST=root@host EME_DEPLOY_KEY=/path/to/key deploy-release.sh vX.Y.Z
# The host also runs the same script from a timer, so this is only needed to deploy sooner or to retry a failed version.
set -euo pipefail

URL_VERSION=https://eme.on8ar.eu/api/version

tag=${1:-}
if ! [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "Usage: $0 vX.Y.Z (got '$tag')" >&2
  exit 2
fi
: "${EME_DEPLOY_HOST:?set EME_DEPLOY_HOST, for example root@94.130.105.135}"
: "${EME_DEPLOY_KEY:?set EME_DEPLOY_KEY to the path of the SSH key}"
version=${tag#v}

ssh_opts=(-i "$EME_DEPLOY_KEY" -o BatchMode=yes)

echo "1/3 Checking that the eme-web unit and the updater are installed on $EME_DEPLOY_HOST"
if ! ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" "systemctl cat eme-web >/dev/null 2>&1 && test -x /usr/local/sbin/eme-web-update"; then
  echo "eme-web.service or /usr/local/sbin/eme-web-update is not installed on $EME_DEPLOY_HOST (see web/deploy/README.md). Nothing was changed." >&2
  exit 1
fi

echo "2/3 Running eme-web-update $tag on $EME_DEPLOY_HOST"
ssh "${ssh_opts[@]}" "$EME_DEPLOY_HOST" "/usr/local/sbin/eme-web-update" "$tag"

echo "3/3 Waiting for $URL_VERSION to report $version"
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "$URL_VERSION" 2>/dev/null | grep -qF "\"version\":\"$version\""; then
    echo "OK: eme.on8ar.eu runs $version"
    exit 0
  fi
  sleep 1
done
echo "FAILED: $URL_VERSION did not report $version within 30 s." >&2
exit 1
