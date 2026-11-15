# Server install (eme.on8ar.eu)

The server updates itself: `eme-web-update.timer` runs `/usr/local/sbin/eme-web-update` about every 15 minutes.
The script installs the latest GitHub release if it has an `eme-web-vX.Y.Z.zip`, checks `/api/version` on
127.0.0.1:5200, and rolls back by itself when the check fails. A version that was rolled back is not
retried by the timer until someone deploys that version by hand. Newer releases are still deployed.

To stay on an older version, first run `systemctl stop eme-web-update.timer`, otherwise the timer moves
the server to the latest release again.

## Host packages

`curl`, `unzip`, `python3`, `flock` (package `util-linux`).

## One-time install (as root on the host)

Run from a checkout of this directory:

```
install -m 755 eme-web-update.sh /usr/local/sbin/eme-web-update
install -m 644 eme-web-update.service eme-web-update.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now eme-web-update.timer
```

`eme-web.service` and the nginx site are installed separately (see the comments in those files).

## First install on a new host

The updater needs a running version to start from, so the first one is put in place by hand. As root:

```
mkdir -p /var/www/eme.on8ar.eu /opt/eme-web /var/lib/eme-web
echo 'EME_VERSION=X.Y.Z' > /etc/default/eme-web
install -m 644 eme-web.service /etc/systemd/system/
```

1. Get `eme-web-vX.Y.Z.zip` from the GitHub release (or build it as `release.yml` does: `dist/` and the
   `eme-web` binary at the root of the zip) and unpack it in an empty directory.
2. Copy `dist/*` to `/var/www/eme.on8ar.eu/` and the `eme-web` binary to `/opt/eme-web/eme-web` (mode 755,
   owned by root, like the updater leaves them). Check the paths against `WEB_DIR` and `BIN_DIR` in `eme-web-update.sh`.
3. Install the nginx site `nginx-eme.on8ar.eu.conf` and reload nginx.
4. `systemctl daemon-reload && systemctl enable --now eme-web`, then `curl 127.0.0.1:5200/api/version`.
5. Do the install of the updater above (the one-time install). From then on the timer updates the host.

## Files that do not travel with a release

The release zip holds only the web app and the server binary. `eme-web.service`, the nginx site,
`eme-web-update.sh` and the timer/service units of the updater are not part of it. When one of them
changes in the repository, install it on the host by hand: copy the file, `systemctl daemon-reload`,
and restart the unit (`systemctl restart eme-web` or `eme-web-update.timer`; nginx: `nginx -t && systemctl reload nginx`).
The `-state-dir` and `StateDirectory=eme-web-server` lines in `eme-web.service` are such a change. The server
keeps the last upstream attempt and the last good copy per source in `/var/lib/eme-web-server/`, so that a
restart neither allows an extra request to W3SZ, N6NU or N0UK nor leaves the web app without data.

The order matters, because a binary older than the `-state-dir` flag (1.1.0 and before) exits with code 2 on
an unknown flag, and the service then restarts in a loop (all `/api` answers 502):

1. First let the timer install a release whose binary knows `-state-dir`. The old unit is still in place and
   does not pass the flag.
2. Then install the new unit (`install -m 644 eme-web.service /etc/systemd/system/`), `systemctl daemon-reload`,
   `systemctl restart eme-web`, and check `curl 127.0.0.1:5200/api/version` and that
   `/var/lib/eme-web-server/last-attempt.json` appears after the first request.

After that, a rollback to a binary older than this change needs the old unit back (without `-state-dir`),
otherwise that binary cannot start.

The `location ^~ /api/hb9q/` block in the nginx site is also such a change. Install it by hand before the
first release with the HB9Q chat: copy the site file, then `nginx -t && systemctl reload nginx`. Without it
a slow HB9Q login hits the 20 s timeout of `/api/` and the web app gets a 504.

The same file holds a per-IP limit for the login: `limit_req_zone ... zone=eme_hb9q_login` at the top and
`location = /api/hb9q/login` with `limit_req` (3 per minute, burst 3, status 429). Without it one client can use
up the server-wide login budget of the Go server (10 per minute) and every user gets 429 `busy`. The zone is
declared at the top level of the site file, which Debian includes in the http context. The `limit_req_zone`
line needs the whole file to be installed, not only the location blocks. `nginx -t` must pass before the reload;
it could not be run when this change was written, so run it on the host.

## Logs

```
journalctl -u eme-web-update
systemctl list-timers eme-web-update.timer
```

## Release run on GitHub

The job `web-deploy` in `.github/workflows/release.yml` does not deploy: SSH on the host is open only to the
maintainer's IP. It polls `https://eme.on8ar.eu/api/version` every 30 s and turns green when the host runs the new
version, which the timer installs within about 16 minutes. It turns red after 25 minutes; then check
`journalctl -u eme-web-update` (the update failed and rolled back, or the timer is stopped).

## Deploy a tag by hand

On the host: `eme-web-update vX.Y.Z`. It also clears the failed-version mark of that version.
From a workstation: `EME_DEPLOY_HOST=root@host EME_DEPLOY_KEY=/path/key web/deploy/deploy-release.sh vX.Y.Z`
(runs the same script over SSH). If another update is running, the script exits with code 75.

## State files

`/var/lib/eme-web/failed-version`: version that failed, skipped by the timer.
`/var/lib/eme-web/in-progress`: set during a swap. If a run finds it, it rolls back first.
