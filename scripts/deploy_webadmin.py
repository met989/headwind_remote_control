"""
Build and deploy Headwind Remote Web-Admin (web-admin.war) to Tomcat 10.

What this script does:
1. Reads host, username, password, and secret_remote from ../secrets.key.
2. Compiles server/web-admin/src/com/hmdm/remote/auth/HmdmAuthFilter.java with JDK 17.
3. Generates/updates server/web-admin/dist/js/settings.js from settings.js.example + secret_remote.
4. Re-bundles dist/css/app.min.css and dist/js/app.min.js from source css/ and js/.
5. Packages web-admin.war (excluding src/, node_modules/, .git/).
6. Uploads ONLY web-admin.war to /var/lib/tomcat10/webapps/web-admin.war (does NOT restart Janus).
7. Verifies unpacked files, SSO filter enforcement (302 / 403), and service health.

Usage:
    python scripts/deploy_webadmin.py [--build-only]
"""

import os
import re
import sys
import shutil
import zipfile
import subprocess


def load_secrets(secrets_path):
    if not os.path.isfile(secrets_path):
        return {}
    data = {}
    with open(secrets_path, "r", encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            data[k.strip()] = v.strip()
    return data


def main():
    build_only = "--build-only" in sys.argv
    script_dir = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(script_dir)
    base = os.path.join(root, "server", "web-admin")
    dist_css = os.path.join(base, "dist", "css")
    dist_js = os.path.join(base, "dist", "js")
    war_path = os.path.join(root, "web-admin.war")

    secrets_path = os.path.join(root, "secrets.key")
    secrets = load_secrets(secrets_path) if os.path.isfile(secrets_path) else {}
    secret_remote = secrets.get("secret_remote", "HEADWIND_REMOTE_DEFAULT_SECRET")

    # 1. Compile Java Servlets and Filters
    javac = os.path.join(root, "_build_tools", "jdk17", "bin", "javac.exe")
    if not os.path.isfile(javac):
        javac = "javac"
    servlet_jar = os.path.join(root, "_build_tools", "servlet-api.jar")
    assert os.path.isfile(servlet_jar), f"Missing {servlet_jar}"

    classes_dir = os.path.join(base, "WEB-INF", "classes")
    settings_path = os.path.join(dist_js, "settings.js")
    settings_tpl = os.path.join(dist_js, "settings.js.example")

    try:
        # 1. Compile all Java sources in src/
        if os.path.isdir(classes_dir):
            shutil.rmtree(classes_dir)
        os.makedirs(classes_dir, exist_ok=True)

        java_sources = []
        for r, _, files in os.walk(os.path.join(base, "src")):
            for fn in files:
                if fn.endswith(".java"):
                    java_sources.append(os.path.join(r, fn))

        res = subprocess.run(
            [javac, "--release", "17", "-cp", servlet_jar, "-d", classes_dir] + java_sources,
            capture_output=True,
            text=True,
        )
        if res.returncode != 0:
            print("JAVAC FAILED:\n", res.stdout, res.stderr)
            raise SystemExit(1)
        print(f"Compiled {len(java_sources)} Java classes into WEB-INF/classes cleanly.")

        # 2. Generate temporary dist/js/settings.js from template (safe, no hardcoded secrets)
        assert os.path.isfile(settings_tpl), f"Missing template {settings_tpl}"
        with open(settings_tpl, "r", encoding="utf-8") as f:
            s_txt = f.read()
        embed_secret = secret_remote if "--embed-secret" in sys.argv else ""
        s_txt = re.sub(r'var apiSecret = "[^"]*";', f'var apiSecret = "{embed_secret}";', s_txt)
        with open(settings_path, "w", encoding="utf-8") as f:
            f.write(s_txt)
        print("Generated temporary dist/js/settings.js (safe for distribution).")

        # 3. Re-bundle dist/css/app.min.css
        app_css_files = [
            os.path.join(base, "css", "main.css"),
            os.path.join(base, "css", "loader.css"),
        ]
        with open(os.path.join(dist_css, "app.min.css"), "w", encoding="utf-8") as out:
            for fp in app_css_files:
                with open(fp, "r", encoding="utf-8", errors="ignore") as inp:
                    out.write(inp.read() + "\n")
        print("Bundled dist/css/app.min.css:", os.path.getsize(os.path.join(dist_css, "app.min.css")), "bytes")

        # 4. Re-bundle dist/js/app.min.js
        app_js_files = [
            os.path.join(base, "js", "janus.js"),
            os.path.join(base, "js", "utils.js"),
            os.path.join(base, "js", "debug-utils.js"),
            os.path.join(base, "js", "cheat-codes.js"),
            os.path.join(base, "js", "keyboard.js"),
            os.path.join(base, "js", "clipboard.js"),
            os.path.join(base, "js", "session-monitoring.js"),
            os.path.join(base, "js", "ui.js"),
            os.path.join(base, "js", "remote-chat.js"),
            os.path.join(base, "js", "commands.js"),
            os.path.join(base, "js", "remote-video.js"),
            os.path.join(base, "js", "video-stats.js"),
            os.path.join(base, "js", "gesture-builder.js"),
            os.path.join(base, "js", "device-info-panel.js"),
            os.path.join(base, "js", "settings-manager.js"),
            os.path.join(base, "js", "remote-admin.js"),
        ]
        app_js_out = os.path.join(dist_js, "app.min.js")
        with open(app_js_out, "w", encoding="utf-8") as out:
            for fp in app_js_files:
                with open(fp, "r", encoding="utf-8", errors="ignore") as inp:
                    out.write(inp.read() + "\n;")
        print("Bundled dist/js/app.min.js:", os.path.getsize(app_js_out), "bytes")

        # 5. Package web-admin.war (excluding src/, settings.js.example, node_modules/, .git/)
        with zipfile.ZipFile(war_path, "w", zipfile.ZIP_DEFLATED) as zipf:
            for r, dirs, files in os.walk(base):
                rel_dir = os.path.relpath(r, base)
                if (
                    rel_dir == "src"
                    or rel_dir.startswith("src" + os.sep)
                    or "node_modules" in r
                    or ".git" in r
                ):
                    continue
                for fn in files:
                    if fn.endswith(".example"):
                        continue
                    full = os.path.join(r, fn)
                    rel = os.path.relpath(full, base)
                    zipf.write(full, rel)
        print("Built web-admin.war:", os.path.getsize(war_path), "bytes")
    finally:
        # Clean up temporary files containing secrets or compiled classes
        if os.path.isfile(settings_path):
            os.remove(settings_path)
            print("Cleaned temporary dist/js/settings.js.")
        if os.path.isdir(classes_dir):
            shutil.rmtree(classes_dir, ignore_errors=True)
            print("Cleaned temporary WEB-INF/classes directory.")

    if build_only:
        print("Build-only mode complete.")
        return

    # 6. Upload web-admin.war via SFTP (requires paramiko)
    import paramiko

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(
        secrets["host"],
        username=secrets["username"],
        password=secrets["password"],
        timeout=15,
    )

    sftp = ssh.open_sftp()
    sftp.put(war_path, "/var/lib/tomcat10/webapps/web-admin.war")
    sftp.close()
    print("Uploaded web-admin.war to /var/lib/tomcat10/webapps/web-admin.war")

    verify_cmd = r"""
chown tomcat:tomcat /var/lib/tomcat10/webapps/web-admin.war
sleep 12
echo "=== VERIFY UNPACKED ASSETS ==="
grep -c "remote-nav-btn" /var/lib/tomcat10/webapps/web-admin/index.html
grep -c "Remote Control" /var/lib/tomcat10/webapps/web-admin/remote-hook.js
grep -c "com.hmdm.remote.auth.HmdmAuthFilter" /var/lib/tomcat10/webapps/web-admin/WEB-INF/web.xml
echo "=== VERIFY SSO FILTER ==="
echo -n "MDM ROOT / (expect 200): "
curl -sk -o /dev/null -w "%{http_code}\n" https://127.0.0.1:8443/
echo -n "remote-hook.js (expect 200): "
curl -sk -o /dev/null -w "%{http_code}\n" https://127.0.0.1:8443/web-admin/remote-hook.js
echo -n "web-admin/ unauth (expect 302): "
curl -sk -o /dev/null -w "%{http_code} -> %{redirect_url}\n" https://127.0.0.1:8443/web-admin/
echo -n "settings.js unauth (expect 403): "
curl -sk -o /dev/null -w "%{http_code}\n" https://127.0.0.1:8443/web-admin/dist/js/settings.js
echo -n "settings.js fake cookie (expect 403): "
curl -sk -H "Cookie: JSESSIONID=FAKE123" -o /dev/null -w "%{http_code}\n" https://127.0.0.1:8443/web-admin/dist/js/settings.js
echo "=== SERVICES ==="
systemctl is-active tomcat10 janus postgresql fail2ban
"""
    _, stdout, stderr = ssh.exec_command(verify_cmd)
    print(stdout.read().decode("utf-8", errors="ignore"))
    err = stderr.read().decode("utf-8", errors="ignore")
    if err:
        print("STDERR:\n" + err)
    ssh.close()


if __name__ == "__main__":
    main()
