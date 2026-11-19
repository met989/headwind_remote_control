#!/usr/bin/env bash
# ==============================================================================
# Headwind Remote Control - Uninstaller Script
# ==============================================================================
set -e

if [ "$EUID" -ne 0 ]; then
    echo "Please run as root: sudo ./uninstall.sh"
    exit 1
fi

echo "=== Uninstalling Headwind Remote Control Plugin ==="

# Stop and remove watchdog
systemctl disable --now headwind-remote-sync.path 2>/dev/null || true
rm -f /etc/systemd/system/headwind-remote-sync.path
rm -f /etc/systemd/system/headwind-remote-sync.service
rm -f /usr/local/bin/headwind-remote-sync
systemctl daemon-reload

# Remove web-admin from Tomcat
for candidate in "/var/lib/tomcat10" "/var/lib/tomcat9" "/opt/tomcat"; do
    if [ -d "$candidate/webapps" ]; then
        rm -f "$candidate/webapps/web-admin.war"
        rm -rf "$candidate/webapps/web-admin"
        INDEX_HTML="$candidate/webapps/ROOT/index.html"
        if [ -f "$INDEX_HTML" ]; then
            sed -i '/remote-hook.js/d' "$INDEX_HTML"
            echo "Removed hook from $INDEX_HTML"
        fi
    fi
done

echo "[OK] Headwind Remote Control plugin uninstalled cleanly."
