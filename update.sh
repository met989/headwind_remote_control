#!/usr/bin/env bash
# ==============================================================================
# Headwind Remote Control - Update & Hook Re-sync Script
# Open Source Remote Screen Sharing and Control Plugin for Headwind MDM
# https://github.com/hmdm-control
# ==============================================================================
# Run this script when:
#  1. Updating Headwind Remote Control to a new release
#  2. Upgrading Headwind MDM server software
#  3. Restoring the "Remote Control" action if missing from the MDM interface
#
# Usage:
#   sudo ./update.sh
# ==============================================================================

set -e

COLOR_RESET="\033[0m"
COLOR_GREEN="\033[1;32m"
COLOR_CYAN="\033[1;36m"
COLOR_YELLOW="\033[1;33m"
COLOR_RED="\033[1;31m"
COLOR_BOLD="\033[1m"

log_info() { echo -e "${COLOR_CYAN}[INFO]${COLOR_RESET} $1"; }
log_ok()   { echo -e "${COLOR_GREEN}[OK]${COLOR_RESET} $1"; }
log_warn() { echo -e "${COLOR_YELLOW}[WARN]${COLOR_RESET} $1"; }
log_err()  { echo -e "${COLOR_RED}[ERROR]${COLOR_RESET} $1"; }

echo -e "${COLOR_BOLD}"
echo "======================================================================"
echo "    Headwind Remote Control - Update & Maintenance Script"
echo "======================================================================"
echo -e "${COLOR_RESET}"

# 1. Root check
if [ "$EUID" -ne 0 ]; then
    log_err "This update script must be run as root. Please run: sudo ./update.sh"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 2. Detect Tomcat Directory
log_info "Detecting Headwind MDM and Tomcat environment..."
TOMCAT_DIR=""
TOMCAT_USER="tomcat"
TOMCAT_SERVICE=""

for candidate in "/var/lib/tomcat10" "/var/lib/tomcat9" "/opt/tomcat"; do
    if [ -d "$candidate/webapps/ROOT" ]; then
        TOMCAT_DIR="$candidate"
        if [ "$candidate" = "/var/lib/tomcat10" ]; then
            TOMCAT_SERVICE="tomcat10"
        elif [ "$candidate" = "/var/lib/tomcat9" ]; then
            TOMCAT_SERVICE="tomcat9"
            TOMCAT_USER="tomcat9"
        fi
        break
    fi
done

if [ -z "$TOMCAT_DIR" ]; then
    log_err "Could not locate Headwind MDM at /var/lib/tomcat10 or /var/lib/tomcat9."
    exit 1
fi
log_ok "Found Headwind MDM at: ${TOMCAT_DIR} (service: ${TOMCAT_SERVICE:-tomcat})"

# 3. Update web-admin.war if present
WAR_SRC="${SCRIPT_DIR}/web-admin.war"
if [ ! -f "$WAR_SRC" ]; then
    WAR_SRC=$(find "${SCRIPT_DIR}" -name "web-admin.war" 2>/dev/null | head -n 1 || true)
fi

if [ -f "$WAR_SRC" ]; then
    log_info "Updating web-admin.war in ${TOMCAT_DIR}/webapps/..."
    cp -f "$WAR_SRC" "${TOMCAT_DIR}/webapps/web-admin.war"
    chown "${TOMCAT_USER}:${TOMCAT_USER}" "${TOMCAT_DIR}/webapps/web-admin.war"
    log_ok "Updated web-admin.war successfully (Tomcat will redeploy automatically)."
else
    log_warn "web-admin.war not found in repository root; skipping WAR update."
fi

# 4. Refresh headwind-remote-sync script
SYNC_SCRIPT="/usr/local/bin/headwind-remote-sync"
log_info "Refreshing sync watchdog script: ${SYNC_SCRIPT}..."

cat <<'EOF' > "$SYNC_SCRIPT"
#!/usr/bin/env bash
# Headwind Remote Control - Hook Sync, Janus Secret Sync & Update Persistence
set -e

TOMCAT_DIR=""
for candidate in "/var/lib/tomcat10" "/var/lib/tomcat9" "/opt/tomcat"; do
    if [ -d "$candidate/webapps/ROOT" ]; then
        TOMCAT_DIR="$candidate"
        break
    fi
done

INDEX_HTML="${TOMCAT_DIR}/webapps/ROOT/index.html"
NGSW_JSON="${TOMCAT_DIR}/webapps/ROOT/ngsw.json"
HOOK_TAG='<script src="/web-admin/remote-hook.js" defer></script>'

if [ -n "$TOMCAT_DIR" ] && [ -f "$INDEX_HTML" ]; then
    if ! grep -q "remote-hook.js" "$INDEX_HTML"; then
        echo "[headwind-remote-sync] Injecting Remote Control hook into ${INDEX_HTML}..."
        sed -i "s|</body>|${HOOK_TAG}</body>|" "$INDEX_HTML"
        chown tomcat:tomcat "$INDEX_HTML" 2>/dev/null || true

        # Update Angular Service Worker hash if ngsw.json exists
        if [ -f "$NGSW_JSON" ] && command -v sha1sum >/dev/null 2>&1; then
            NEW_SHA=$(sha1sum "$INDEX_HTML" | awk '{print $1}')
            sed -i -E "s|(\"/index.html\"[[:space:]]*:[[:space:]]*)\"[a-f0-9]{40}\"|\1\"${NEW_SHA}\"|g" "$NGSW_JSON"
            chown tomcat:tomcat "$NGSW_JSON" 2>/dev/null || true
            echo "[headwind-remote-sync] Synced ngsw.json SHA-1 hash to: ${NEW_SHA}"
        fi
    fi
fi

# Sync Janus API Secret from headwind-remote.json to /etc/janus/janus.jcfg
CFG_FILE=""
for c in "/etc/tomcat10/Catalina/headwind-remote.json" "/var/lib/tomcat10/conf/headwind-remote.json" "/etc/headwind-remote/config.json"; do
    if [ -f "$c" ]; then
        CFG_FILE="$c"
        break
    fi
done

if [ -n "$CFG_FILE" ] && [ -f "/etc/janus/janus.jcfg" ]; then
    NEW_SECRET=$(grep -o '"apiSecret"[[:space:]]*:[[:space:]]*"[^"]*"' "$CFG_FILE" | sed -E 's/.*"apiSecret"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/' || true)
    if [ -n "$NEW_SECRET" ]; then
        CUR_SECRET=$(grep -E '^\s*api_secret\s*=\s*"[^"]*"' /etc/janus/janus.jcfg | sed -E 's/.*"([^"]*)".*/\1/' || true)
        if [ "$NEW_SECRET" != "$CUR_SECRET" ]; then
            echo "[headwind-remote-sync] Syncing Janus api_secret to: ${NEW_SECRET}..."
            sed -i -E "s|^([[:space:]]*api_secret[[:space:]]*=[[:space:]]*\")[^\"]*(\".*)|\1${NEW_SECRET}\2|" /etc/janus/janus.jcfg
            if systemctl is-active --quiet janus; then
                systemctl restart janus
                echo "[headwind-remote-sync] Janus WebRTC gateway restarted with new secret."
            fi
        fi
    fi
fi
EOF

chmod +x "$SYNC_SCRIPT"
"$SYNC_SCRIPT"
log_ok "Executed hook sync (MDM interface refreshed)."

# 5. Ensure systemd watchdog units are active
SYSTEMD_PATH="/etc/systemd/system/headwind-remote-sync.path"
if [ -f "$SYSTEMD_PATH" ]; then
    systemctl daemon-reload
    systemctl enable --now headwind-remote-sync.path >/dev/null 2>&1 || true
    log_ok "Update watchdog service verified and active."
fi

# 6. Verify Services Status
log_info "Verifying background services..."
if systemctl is-active --quiet janus; then
    log_ok "Janus WebRTC Gateway: ACTIVE"
else
    log_warn "Janus WebRTC Gateway is not active. Attempting restart..."
    systemctl restart janus || true
fi

if [ -n "$TOMCAT_SERVICE" ]; then
    if systemctl is-active --quiet "$TOMCAT_SERVICE"; then
        log_ok "${TOMCAT_SERVICE}: ACTIVE"
    else
        log_warn "${TOMCAT_SERVICE} is not active. Starting..."
        systemctl start "$TOMCAT_SERVICE" || true
    fi
fi

# 7. Summary
echo ""
echo -e "${COLOR_GREEN}${COLOR_BOLD}======================================================================${COLOR_RESET}"
echo -e "${COLOR_GREEN}${COLOR_BOLD}     Headwind Remote Control Updated Successfully!                    ${COLOR_RESET}"
echo -e "${COLOR_GREEN}${COLOR_BOLD}======================================================================${COLOR_RESET}"
echo ""
echo -e "  - Web-Admin plugin: Updated and active."
echo -e "  - Hook auto-sync:   Verified."
echo -e "  - Android APK:      If updated, upload ${COLOR_BOLD}headwind-remote-custom.apk${COLOR_RESET} to"
echo -e "                      Headwind MDM under ${COLOR_CYAN}Applications${COLOR_RESET} -> ${COLOR_CYAN}com.hmdm.control${COLOR_RESET}."
echo ""
