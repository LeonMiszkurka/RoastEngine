#!/usr/bin/env bash
# Installs (or updates) the RoastEngine server on an Ubuntu machine, such as an AWS EC2
# instance. Run from the unzipped folder:
#
#   sudo bash roastengine-server/deploy/setup.sh
#
# Safe to run again after uploading a newer zip: the program is replaced, server.properties is kept.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Run this with sudo:  sudo bash $0" >&2
    exit 1
fi

HERE="$(cd "$(dirname "$0")/.." && pwd)"     # the unzipped roastengine-server folder
TARGET=/opt/roastengine-server

echo "== Installing Java (if needed)"
if ! command -v java >/dev/null 2>&1; then
    apt-get update -q
    apt-get install -y -q openjdk-21-jre-headless
fi
java -version 2>&1 | head -1

echo "== Creating the service user"
id roastengine >/dev/null 2>&1 || useradd --system --home "$TARGET" --shell /usr/sbin/nologin roastengine

echo "== Copying the server to $TARGET"
mkdir -p "$TARGET/data"
rm -rf "$TARGET/app"
cp -r "$HERE" "$TARGET/app"
chown -R roastengine:roastengine "$TARGET/data"

# On a cloud machine, sleep when nobody plays. Only added if not already set, so a value you
# changed yourself is kept when updating.
SETTINGS="$TARGET/data/server.properties"
if ! grep -qs '^idleShutdownMinutes' "$SETTINGS"; then
    echo "idleShutdownMinutes=15" >> "$SETTINGS"
    chown roastengine:roastengine "$SETTINGS"
fi

# server.properties holds the account service's serverKey: only the server may read it.
chmod 600 "$SETTINGS"

echo "== Installing the service"
cp "$HERE/deploy/roastengine-server.service" /etc/systemd/system/roastengine-server.service
systemctl daemon-reload
systemctl enable roastengine-server >/dev/null
systemctl restart roastengine-server

sleep 3
if systemctl is-active --quiet roastengine-server; then
    echo
    echo "RoastEngine server is running."
    echo "  Settings: $TARGET/data/server.properties  (then: sudo systemctl restart roastengine-server)"
    echo "  Logs:     sudo journalctl -u roastengine-server -f"
    echo "  It powers the machine off after 15 minutes with nobody on (idleShutdownMinutes)."
    echo "  Players join through the wake-up link, which starts it again."
else
    echo "The server did not start. Recent log:" >&2
    journalctl -u roastengine-server -n 30 --no-pager >&2
    exit 1
fi
