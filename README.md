# Headwind Remote Control Plugin for Headwind MDM

[![Headwind MDM](https://img.shields.io/badge/Headwind%20MDM-Compatible%20v7+-blue.svg)](https://h-mdm.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Ubuntu%20%7C%20Debian%20%7C%20Android-orange.svg)](#prerequisites)

A modern, high-performance, open-source **Remote Screen Sharing & Control Plugin** designed for **Headwind MDM**.

Enables administrators and support staff to remotely view and control managed Android devices directly inside the browser with **one click**, sub-second latency, and zero supervisor software installation.

---

## ✨ Key Features

- **🚀 1-Click Remote Control:** A seamless "Remote Control" action integrated directly into the Headwind MDM Devices table.
- **⚡ Ultra Low-Latency WebRTC:** Powered by Janus WebRTC Gateway for fluid, real-time screen interaction.
- **⚙️ Full Web GUI Configuration:** Configure Janus signaling ports, API secrets, default quality, and STUN/TURN servers directly inside the Web UI (no manual config files or secrets editing required).
- **🔄 Headwind MDM Update Persistence:** Built-in watchdog service (`headwind-remote-sync`) ensures that when you upgrade or update Headwind MDM, your buttons and hooks are automatically restored within seconds.
- **📱 Universal Android APK:** Dynamic configuration injection via native MDM push notifications. The APK automatically receives the server URL and credentials on every session.
- **🔒 Single Sign-On (SSO):** Enforces Headwind MDM session validation on all endpoints (`HmdmAuthFilter`). Unauthorized users are redirected to login.
- **📊 Real-time Device Details Sidebar:** Collapsible panel showing live hardware specs, battery level, network info, and MDM policy permissions.
- **🎛️ Live Video Quality Switching:** Dynamically adjust resolution, bitrate, and framerate mid-session (`Low`, `Medium`, `High`, `Ultra`) without disconnecting.
- **⌨️ Remote Gestures & Navigation:** Virtual Android navigation bar (Back, Home, Recents) plus status bar expansion and keyboard text synchronization.

---

## 📋 Prerequisites

- **Server:** A clean installation of **Headwind MDM** (v7.0+) running on **Ubuntu (20.04, 22.04, 24.04 LTS)** or **Debian**.
- **Tomcat:** Apache Tomcat 10 or 9 (installed at `/var/lib/tomcat10` or `/var/lib/tomcat9`).
- **SSL / HTTPS:** A valid domain with SSL certificates (e.g., Let's Encrypt at `/etc/letsencrypt/live/<domain>/`).
- **Network Ports:**
  - `8089/tcp` - Janus HTTPS signaling
  - `8989/tcp` - Janus WSS WebSocket signaling
  - `20000-40000/udp` - WebRTC RTP media streams

---

## 🚀 Quick Automated Installation

Run the installer on your Headwind MDM server:

```bash
# 1. Clone the repository
git clone https://github.com/<your-username>/headwind_remote_control.git
cd headwind_remote_control

# 2. Run the automated installer as root
sudo chmod +x install.sh update.sh uninstall.sh
sudo ./install.sh
```

### What `install.sh` does automatically:
1. Detects your Tomcat version, domain name, and Let's Encrypt SSL certificates.
2. Installs Janus WebRTC Gateway and system libraries.
3. Configures Janus with HTTPS (`8089`) and secure WebSocket (`8989`).
4. Generates a secure random API secret and saves it to `/var/lib/tomcat10/conf/headwind-remote.json`.
5. Deploys `web-admin.war` into Tomcat.
6. Injects the "Remote Control" button into the Headwind MDM web interface (`ROOT/index.html`).
7. Installs the `headwind-remote-sync` watchdog to maintain UI integration across Headwind MDM upgrades.
8. Configures UFW firewall rules automatically.

---

## ⚙️ Web GUI Configuration

All plugin settings can be managed directly in the browser:

1. Open your browser and go to `https://<your-domain>/web-admin/` (or launch a remote session from the MDM panel).
2. Click the **⚙️ Settings** icon located on the login card or top navigation bar.
3. Configure your preferences:
   - **WebSocket Port (WSS):** Default `8989`
   - **HTTPS REST Port:** Default `8089`
   - **Janus API Secret:** Click **Generate** or enter a custom secret (automatically synced with `/etc/janus/janus.jcfg`).
   - **Default Video Quality:** Choose between Low, Medium (1 Mbps / 15 fps), High (2 Mbps / 20 fps), or Ultra.
   - **STUN / TURN Server:** Configure custom TURN credentials for strict corporate firewalls.
4. Click **Test Janus Connection** to verify gateway health in 1 click.
5. Click **Save Configuration** — changes take effect immediately without restarting Tomcat or Janus.

---

## 📱 Android Agent Setup

1. Log in to your **Headwind MDM Web Panel** (`https://<your-domain>/`).
2. Navigate to **Applications** -> click **+ Add**.
3. Fill in the application details:
   - **Application Name:** `Headwind Remote`
   - **Package ID:** `com.hmdm.control`
   - **File:** Upload the pre-built `headwind-remote-custom.apk` from this repository.
4. Open your **Configurations** tab and ensure `com.hmdm.control` is enabled for your device group.
5. Once installed on the device, grant the required accessibility service permission when prompted.
6. Open **Devices**, click the **3-dots menu (⋮)** on any enrolled device, and select **Remote Control**!

---

## 🔄 Headwind MDM Update Persistence

When you upgrade or reinstall Headwind MDM, the server often overwrites `ROOT/index.html`.

This plugin includes an automated systemd watchdog:
- **Service:** `headwind-remote-sync.path` + `headwind-remote-sync.service`
- Whenever `ROOT/index.html` is replaced or modified, systemd immediately runs `/usr/local/bin/headwind-remote-sync`.
- The hook `<script src="/web-admin/remote-hook.js" defer></script>` is automatically re-injected, and the Angular Service Worker SHA-1 hash in `ngsw.json` is updated.

To manually trigger a re-sync at any time:
```bash
sudo ./update.sh
```

---

## 📦 Publishing Updates to GitHub (For Maintainers)

On Windows, double-click or run:
```cmd
publish_github.bat
```
This batch script will:
1. Verify `.gitignore` rules (ensuring no secrets or private keys are committed).
2. Initialize git if not already present.
3. Prompt for a commit message.
4. Push updates to the `main` branch on GitHub.

---

## 🗑️ Uninstallation

To remove the plugin cleanly:
```bash
sudo ./uninstall.sh
```
This removes `web-admin.war`, cleans the hook from `ROOT/index.html`, and disables the systemd watchdog.

---

## 📄 License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for details.
