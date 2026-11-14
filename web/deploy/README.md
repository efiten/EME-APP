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

## Logs

```
journalctl -u eme-web-update
systemctl list-timers eme-web-update.timer
```

## Deploy a tag by hand

On the host: `eme-web-update vX.Y.Z`. It also clears the failed-version mark of that version.
From a workstation: `EME_DEPLOY_HOST=root@host EME_DEPLOY_KEY=/path/key web/deploy/deploy-release.sh vX.Y.Z`
(runs the same script over SSH). If another update is running, the script exits with code 75.

## State files

`/var/lib/eme-web/failed-version`: version that failed, skipped by the timer.
`/var/lib/eme-web/in-progress`: set during a swap. If a run finds it, it rolls back first.
