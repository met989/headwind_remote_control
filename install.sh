#!/usr/bin/env bash
# ==============================================================================
# Headwind Remote Control - Automated Installer for Headwind MDM
# Open Source Remote Screen Sharing and Control Plugin for Headwind MDM
# https://github.com/hmdm-control
# ==============================================================================
# Designed for clean Ubuntu (20.04, 22.04, 24.04 LTS) and Debian systems with
# Headwind MDM already installed and running on Tomcat 10 or 9.
#
# Usage:
#   sudo ./install.sh
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
echo "    Headwind Remote Control - Community Plugin Installer"
echo "    Open Source Remote Screen Sharing & Control for Headwind MDM"
echo "======================================================================"
echo -e "${COLOR_RESET}"

# 1. Root check
if [ "$EUID" -ne 0 ]; then
    log_err "This installer must be run as root. Please run: sudo ./install.sh"
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
    log_err "Please ensure Headwind MDM is installed before installing this plugin."
    exit 1
fi
log_ok "Found Headwind MDM at: ${TOMCAT_DIR} (service: ${TOMCAT_SERVICE})"

# 3. Detect Domain Name & SSL Certificates
DOMAIN_NAME=""
# Try detecting from Let's Encrypt live directory
if [ -d "/etc/letsencrypt/live" ]; then
    for d in /etc/letsencrypt/live/*; do
        if [ -d "$d" ] && [ -f "$d/fullchain.pem" ]; then
            DOMAIN_NAME="$(basename "$d")"
            break
        fi
    done
fi

# Fallback: check nginx configs
if [ -z "$DOMAIN_NAME" ] && [ -d "/etc/nginx/sites-enabled" ]; then
    DOMAIN_NAME=$(grep -r "server_name" /etc/nginx/sites-enabled/ 2>/dev/null | awk '{print $2}' | tr -d ';' | grep -v "localhost" | head -n 1 || true)
fi

# Fallback: prompt or hostname
if [ -z "$DOMAIN_NAME" ]; then
    DEFAULT_HOST=$(hostname -f 2>/dev/null || hostname)
    read -r -p "Enter your Headwind MDM domain name (e.g. mdm.example.com) [${DEFAULT_HOST}]: " USER_INPUT_DOMAIN
    DOMAIN_NAME="${USER_INPUT_DOMAIN:-$DEFAULT_HOST}"
fi
log_ok "Detected MDM domain: ${DOMAIN_NAME}"

# Detect SSL Cert files
CERT_PEM="/etc/letsencrypt/live/${DOMAIN_NAME}/fullchain.pem"
KEY_PEM="/etc/letsencrypt/live/${DOMAIN_NAME}/privkey.pem"

if [ ! -f "$CERT_PEM" ] || [ ! -f "$KEY_PEM" ]; then
    log_warn "Let's Encrypt certificates not found at /etc/letsencrypt/live/${DOMAIN_NAME}."
    # Look for any valid cert
    ALT_CERT=$(find /etc/ssl/certs/ /etc/letsencrypt/live/ -name "*fullchain.pem" -o -name "*.crt" 2>/dev/null | head -n 1 || true)
    ALT_KEY=$(find /etc/ssl/private/ /etc/letsencrypt/live/ -name "*privkey.pem" -o -name "*.key" 2>/dev/null | head -n 1 || true)
    if [ -n "$ALT_CERT" ] && [ -n "$ALT_KEY" ]; then
        CERT_PEM="$ALT_CERT"
        KEY_PEM="$ALT_KEY"
        log_ok "Using alternative SSL certificates: ${CERT_PEM}"
    else
        log_warn "SSL certificates not detected. Janus WebRTC will require manual certificate setup if HTTPS/WSS fails."
    fi
else
    log_ok "Using Let's Encrypt SSL certificates from: ${CERT_PEM}"
fi

# 4. Install Dependencies
log_info "Installing required packages (Janus Gateway, libmicrohttpd, curl, jq)..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq janus libmicrohttpd-dev libjansson-dev libssl-dev libsrtp2-dev libglib2.0-dev curl jq openssl
log_ok "Dependencies installed successfully."

# 5. Generate / Preserve Janus API Secret
JANUS_CONFIG_DIR="/etc/janus"
JANUS_MAIN_CONF="${JANUS_CONFIG_DIR}/janus.jcfg"
JANUS_HTTP_CONF="${JANUS_CONFIG_DIR}/janus.transport.http.jcfg"
JANUS_WS_CONF="${JANUS_CONFIG_DIR}/janus.transport.websockets.jcfg"

API_SECRET=""
if [ -f "$JANUS_MAIN_CONF" ]; then
    API_SECRET=$(grep -E '^\s*api_secret\s*=' "$JANUS_MAIN_CONF" | head -n 1 | awk -F'"' '{print $2}' || true)
fi

if [ -z "$API_SECRET" ]; then
    API_SECRET=$(openssl rand -hex 16)
    log_info "Generated new secure Janus API secret: ${API_SECRET:0:6}********"
else
    log_info "Reusing existing Janus API secret: ${API_SECRET:0:6}********"
fi

# 6. Configure Janus Gateway
log_info "Configuring Janus WebRTC Gateway..."
mkdir -p "$JANUS_CONFIG_DIR"

# Detect dynamic Janus library paths based on architecture (x86_64, aarch64, etc.)
JANUS_PLUGINS_FOUND=$(find /usr/lib -type d -path "*/janus/plugins" 2>/dev/null | head -n 1 || true)
if [ -n "$JANUS_PLUGINS_FOUND" ]; then
    JANUS_BASE="$(dirname "$JANUS_PLUGINS_FOUND")"
else
    ARCH_NAME=$(uname -m)
    if [ -d "/usr/lib/${ARCH_NAME}-linux-gnu/janus" ]; then
        JANUS_BASE="/usr/lib/${ARCH_NAME}-linux-gnu/janus"
    else
        JANUS_BASE="/usr/lib/janus"
    fi
fi
log_ok "Janus library path detected: ${JANUS_BASE}"

# janus.jcfg
cat <<EOF > "$JANUS_MAIN_CONF"
general: {
	configs_folder = "/etc/janus"
	plugins_folder = "${JANUS_BASE}/plugins"
	transports_folder = "${JANUS_BASE}/transports"
	events_folder = "${JANUS_BASE}/events"
	loggers_folder = "${JANUS_BASE}/loggers"

	api_secret = "${API_SECRET}"
	debug_level = 3
	log_to_stdout = false
}

nat: {
	stun_server = "stun.l.google.com"
	stun_port = 19302
	nice_debug = false
}

media: {
	rtp_port_range = "20000-40000"
}
EOF

# janus.transport.http.jcfg
cat <<EOF > "$JANUS_HTTP_CONF"
general: {
	json = "indented"
	base_path = "/janus"
	http = false
	https = true
	secure_port = 8089
}

admin: {
	admin_base_path = "/admin"
	admin_http = false
	admin_https = false
}

cors: {
	# Allow cross-origin requests from both :443 and other ports
}

certificates: {
	cert_pem = "${CERT_PEM}"
	cert_key = "${KEY_PEM}"
}
EOF

# janus.transport.websockets.jcfg
cat <<EOF > "$JANUS_WS_CONF"
general: {
	json = "indented"
	ws = false
	wss = true
	wss_port = 8989
}

admin: {
	admin_ws = false
	admin_wss = false
}

cors: {
	# Allow cross-origin requests
}

certificates: {
	cert_pem = "${CERT_PEM}"
	cert_key = "${KEY_PEM}"
}
EOF

log_ok "Janus Gateway configuration files written."

# Restart Janus
log_info "Enabling and starting Janus service..."
systemctl enable janus
systemctl restart janus
sleep 2

if systemctl is-active --quiet janus; then
    log_ok "Janus service is active and running."
else
    log_warn "Janus service did not start cleanly. Check 'journalctl -u janus -n 50'."
fi

# 7. Configure Plugin Settings JSON
mkdir -p "${TOMCAT_DIR}/conf" "/etc/${TOMCAT_SERVICE}/Catalina" 2>/dev/null || true
chown -R "${TOMCAT_USER}:${TOMCAT_USER}" "/etc/${TOMCAT_SERVICE}/Catalina" 2>/dev/null || true
chmod 775 "/etc/${TOMCAT_SERVICE}/Catalina" 2>/dev/null || true

REMOTE_CONF_FILE="/etc/${TOMCAT_SERVICE}/Catalina/headwind-remote.json"
FALLBACK_CONF_FILE="${TOMCAT_DIR}/conf/headwind-remote.json"
log_info "Saving initial plugin configuration to: ${REMOTE_CONF_FILE}"

if [ ! -f "$REMOTE_CONF_FILE" ]; then
    cat <<EOF > "$REMOTE_CONF_FILE"
{
  "portApiHttps": "8089",
  "portApiWss": "8989",
  "apiSecret": "${API_SECRET}",
  "defaultQuality": "high",
  "autoTimeoutMs": "2200",
  "maxRetries": "12",
  "serverUrl": "https://${DOMAIN_NAME}:8089/",
  "stunServer": "stun:stun.l.google.com:19302",
  "turnServer": "",
  "turnUsername": "",
  "turnPassword": ""
}
EOF
else
    # Update apiSecret in existing file if missing
    log_info "Updating apiSecret in existing configuration file..."
    TMP_JSON=$(mktemp)
    jq --arg sec "$API_SECRET" --arg url "https://${DOMAIN_NAME}:8089/" '.apiSecret = $sec | .serverUrl = (if .serverUrl == "" or .serverUrl == null then $url else .serverUrl end)' "$REMOTE_CONF_FILE" > "$TMP_JSON" && mv "$TMP_JSON" "$REMOTE_CONF_FILE"
fi

# Ensure both paths are in sync
cp -f "$REMOTE_CONF_FILE" "$FALLBACK_CONF_FILE" 2>/dev/null || true

chown "${TOMCAT_USER}:${TOMCAT_USER}" "$REMOTE_CONF_FILE" "$FALLBACK_CONF_FILE" 2>/dev/null || true
chmod 664 "$REMOTE_CONF_FILE" "$FALLBACK_CONF_FILE" 2>/dev/null || true
log_ok "Plugin configuration file secured (mode 664, owner ${TOMCAT_USER})."

# Allow Tomcat to write configuration files under /conf and /etc (Ubuntu/Debian systemd sandboxing)
if [ -n "$TOMCAT_SERVICE" ]; then
    mkdir -p "/etc/systemd/system/${TOMCAT_SERVICE}.service.d"
    cat << EOF > "/etc/systemd/system/${TOMCAT_SERVICE}.service.d/headwind-remote.conf"
[Service]
ReadWritePaths=${TOMCAT_DIR}/conf /etc/${TOMCAT_SERVICE} /etc/${TOMCAT_SERVICE}/Catalina
EOF
    systemctl daemon-reload
    systemctl restart "${TOMCAT_SERVICE}"
    log_ok "Granted write permissions to ${TOMCAT_SERVICE} for plugin configuration."
fi

# 8. Deploy web-admin.war
WAR_SRC="${SCRIPT_DIR}/web-admin.war"
if [ ! -f "$WAR_SRC" ]; then
    # Look in parent or build directory
    WAR_SRC=$(find "${SCRIPT_DIR}" -name "web-admin.war" 2>/dev/null | head -n 1 || true)
fi

if [ -f "$WAR_SRC" ]; then
    log_info "Deploying web-admin.war to ${TOMCAT_DIR}/webapps/web-admin.war..."
    cp -f "$WAR_SRC" "${TOMCAT_DIR}/webapps/web-admin.war"
    chown "${TOMCAT_USER}:${TOMCAT_USER}" "${TOMCAT_DIR}/webapps/web-admin.war"
    log_ok "Uploaded web-admin.war (Tomcat will unpack automatically)."
else
    log_warn "Pre-built web-admin.war not found in repository root. If building from source, please run deploy_webadmin.py first."
fi

# 9. Install Watchdog, Janus Secret Auto-Sync & Inject Hook into Headwind MDM ROOT/index.html
SYNC_SCRIPT="/usr/local/bin/headwind-remote-sync"
log_info "Installing Headwind MDM update & Janus sync watchdog to: ${SYNC_SCRIPT}..."

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

# Run initial sync
"$SYNC_SCRIPT"
log_ok "Remote Control button injected and Janus watchdog active."

# 10. Install Systemd Path & Service for Automatic MDM Update & Config Sync Resilience
SYSTEMD_SERVICE="/etc/systemd/system/headwind-remote-sync.service"
SYSTEMD_PATH="/etc/systemd/system/headwind-remote-sync.path"

cat <<EOF > "$SYSTEMD_SERVICE"
[Unit]
Description=Headwind Remote Control Hook & Config Sync Watchdog
After=${TOMCAT_SERVICE}.service

[Service]
Type=oneshot
ExecStart=${SYNC_SCRIPT}
EOF

cat <<EOF > "$SYSTEMD_PATH"
[Unit]
Description=Watch Headwind MDM index.html and Settings JSON for Changes
After=${TOMCAT_SERVICE}.service

[Path]
PathModified=${TOMCAT_DIR}/webapps/ROOT/index.html
PathModified=/etc/${TOMCAT_SERVICE}/Catalina/headwind-remote.json
PathModified=${TOMCAT_DIR}/conf/headwind-remote.json
Unit=headwind-remote-sync.service

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now headwind-remote-sync.path
log_ok "Update watchdog enabled: any Headwind MDM update will automatically restore the buttons within seconds."

# 11. Firewall Configuration (UFW)
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    log_info "Configuring UFW firewall rules..."
    ufw allow 8089/tcp comment "Headwind Remote Janus HTTPS" >/dev/null 2>&1 || true
    ufw allow 8989/tcp comment "Headwind Remote Janus WSS" >/dev/null 2>&1 || true
    ufw allow 20000:40000/udp comment "Headwind Remote WebRTC RTP" >/dev/null 2>&1 || true
    log_ok "Firewall ports 8089/tcp, 8989/tcp, 20000-40000/udp allowed in UFW."
fi

# 12. Success Banner
echo ""
echo -e "${COLOR_GREEN}${COLOR_BOLD}======================================================================${COLOR_RESET}"
echo -e "${COLOR_GREEN}${COLOR_BOLD}     Headwind Remote Control Plugin Installed Successfully!          ${COLOR_RESET}"
echo -e "${COLOR_GREEN}${COLOR_BOLD}======================================================================${COLOR_RESET}"
echo ""
echo -e "  ${COLOR_BOLD}Web Administrator:${COLOR_RESET} https://${DOMAIN_NAME}/web-admin/"
echo -e "  ${COLOR_BOLD}MDM Control Panel:${COLOR_RESET} https://${DOMAIN_NAME}/"
echo -e "  ${COLOR_BOLD}Configuration:${COLOR_RESET}    Click the ⚙️ Settings button in the Web GUI"
echo -e "  ${COLOR_BOLD}Config File:${COLOR_RESET}      ${REMOTE_CONF_FILE}"
echo ""
echo -e "  ${COLOR_BOLD}Android Agent Setup:${COLOR_RESET}"
echo -e "  1. In Headwind MDM, navigate to ${COLOR_CYAN}Applications${COLOR_RESET} -> ${COLOR_CYAN}+ Add${COLOR_RESET}."
echo -e "  2. Upload ${COLOR_BOLD}headwind-remote-custom.apk${COLOR_RESET} (package: com.hmdm.control)."
echo -e "  3. Add the application to your Configurations."
echo -e "  4. In the ${COLOR_CYAN}Devices${COLOR_RESET} table, click the 3-dots menu -> ${COLOR_GREEN}Remote Control${COLOR_RESET}!"
echo ""
echo -e "  ${COLOR_BOLD}Headwind MDM Updates:${COLOR_RESET}"
echo -e "  The ${COLOR_CYAN}headwind-remote-sync${COLOR_RESET} watchdog is active. When you upgrade Headwind MDM,"
echo -e "  the 'Remote Control' button will automatically be re-injected."
echo ""
