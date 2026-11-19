"""
Build and sign the custom Headwind Remote Android APK (com.hmdm.control).

Reads server_remote, secret_remote, versionname (and optional keystore overrides) from ../secrets.key.
Uses the portable JDK 17 and Android SDK 33 under ../_build_tools/ (or falls back to JAVA_HOME/ANDROID_HOME).
Ensures gradle-wrapper.properties remains pinned to Gradle 7.5.1 (required by AGP 7.4.2).

Output:
    ../headwind-remote-custom.apk

Usage:
    python scripts/build_android_apk.py
"""

import os
import re
import sys
import shutil
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


def ensure_gradle_wrapper(client_dir):
    wrapper_props = os.path.join(client_dir, "gradle", "wrapper", "gradle-wrapper.properties")
    with open(wrapper_props, "r", encoding="utf-8") as f:
        txt = f.read()
    expected = "gradle-7.5.1-bin.zip"
    if expected not in txt:
        txt = re.sub(
            r"distributionUrl=.*",
            r"distributionUrl=https\://services.gradle.org/distributions/gradle-7.5.1-bin.zip",
            txt,
        )
        with open(wrapper_props, "w", encoding="utf-8") as f:
            f.write(txt)
        print("Restored gradle-wrapper.properties to Gradle 7.5.1.")


def ensure_keystore(root, jdk_bin, secrets):
    ks_file = secrets.get("keystore_file") or "custom-remote.jks"
    ks_path = os.path.normpath(os.path.join(root, ks_file.replace("../../", "")))
    ks_pass = secrets.get("keystore_password") or "custom_release_key"
    ks_alias = secrets.get("keystore_alias") or "custom_remote"

    if os.path.isfile(ks_path):
        return ks_path

    keytool = os.path.join(jdk_bin, "keytool.exe" if os.name == "nt" else "keytool")
    print("Keystore not found; generating a new release keystore (custom-remote.jks)...")
    cmd = [
        keytool,
        "-genkeypair",
        "-v",
        "-keystore",
        ks_path,
        "-storetype",
        "PKCS12",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "10000",
        "-alias",
        ks_alias,
        "-storepass",
        ks_pass,
        "-keypass",
        ks_pass,
        "-dname",
        "CN=Headwind Remote, OU=custom, O=custom, L=HQ, S=State, C=US",
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="latin-1", errors="replace")
    if res.returncode != 0:
        print("KEYTOOL FAILED:\n", res.stdout, res.stderr)
        raise SystemExit(1)
    print("Generated release keystore successfully.")
    return ks_path


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(script_dir)
    client_dir = os.path.join(root, "client-android")
    secrets = load_secrets(os.path.join(root, "secrets.key"))

    ensure_gradle_wrapper(client_dir)

    jdk_home = os.path.join(root, "_build_tools", "jdk17")
    sdk_home = os.path.join(root, "_build_tools", "android-sdk")
    env = os.environ.copy()
    if os.path.isdir(jdk_home):
        env["JAVA_HOME"] = jdk_home
        env["PATH"] = os.path.join(jdk_home, "bin") + os.pathsep + env.get("PATH", "")
    if os.path.isdir(sdk_home):
        env["ANDROID_HOME"] = sdk_home
        env["ANDROID_SDK_ROOT"] = sdk_home

    ensure_keystore(root, os.path.join(env["JAVA_HOME"], "bin"), secrets)

    gradlew = os.path.join(client_dir, "gradlew.bat" if os.name == "nt" else "gradlew")
    is_generic = "--generic" in sys.argv
    gradle_cmd = [gradlew, "assembleRelease", "--no-daemon"]
    if is_generic:
        gradle_cmd.append("-Pgeneric=true")
        print("Building generic release APK for public distribution (no embedded domain/secret)...")
    else:
        print("Building release APK (custom)...")
    try:
        res = subprocess.run(gradle_cmd, cwd=client_dir, env=env)
        if res.returncode != 0:
            raise SystemExit(res.returncode)

        built_apk = os.path.join(client_dir, "app", "build", "outputs", "apk", "release", "app-release.apk")
        out_apk = os.path.join(root, "headwind-remote-custom.apk")
        shutil.copy2(built_apk, out_apk)
        print(f"Built signed APK: {out_apk} ({os.path.getsize(out_apk) / (1024 * 1024):.2f} MB)")

        apksigner = os.path.join(sdk_home, "build-tools", "33.0.2", "apksigner.bat" if os.name == "nt" else "apksigner")
        if os.path.isfile(apksigner):
            v = subprocess.run([apksigner, "verify", "--verbose", out_apk], env=env, capture_output=True, text=True)
            print("APK signature verify rc:", v.returncode)
            if v.returncode != 0:
                print(v.stdout, v.stderr)
                raise SystemExit(1)
    finally:
        # Clean up temporary build folders (including generated BuildConfig.java containing secrets)
        for tmp_dir in [
            os.path.join(client_dir, "app", "build"),
            os.path.join(client_dir, "build"),
        ]:
            if os.path.isdir(tmp_dir):
                shutil.rmtree(tmp_dir, ignore_errors=True)
                print("Cleaned temporary build dir:", tmp_dir)


if __name__ == "__main__":
    main()
