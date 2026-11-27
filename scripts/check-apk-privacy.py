"""Inspect the offline release APK manifest and DEX classes to enforce zero-internet isolation."""
import argparse
import os
import shutil
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("--apkanalyzer", default=shutil.which("apkanalyzer"))
args = parser.parse_args()
analyzer = args.apkanalyzer
if not analyzer:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local_props = Path(__file__).resolve().parent.parent / "local.properties"
        if local_props.is_file():
            for line in local_props.read_text(encoding="utf-8").splitlines():
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
                    break
    if not sdk:
        parser.error("Set ANDROID_HOME or pass --apkanalyzer")
    name = "apkanalyzer.bat" if os.name == "nt" else "apkanalyzer"
    analyzer = Path(sdk) / "cmdline-tools" / "latest" / "bin" / name
    if not Path(analyzer).exists():
        # Fallback search inside cmdline-tools versions
        candidates = list((Path(sdk) / "cmdline-tools").glob(f"*/bin/{name}"))
        if candidates:
            analyzer = candidates[0]

result = subprocess.run([str(analyzer), "manifest", "print", str(args.apk)], check=True, stdout=subprocess.PIPE)
root = ET.fromstring(result.stdout)
android = "{http://schemas.android.com/apk/res/android}"
permissions = {node.get(android + "name") for node in root if node.tag.startswith("uses-permission")}
blocked = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"} & permissions
if blocked:
    raise SystemExit("Unexpected network permissions: " + ", ".join(sorted(blocked)))
app = root.find("application")
if app is None:
    raise SystemExit("Missing application manifest")
for name in ("allowBackup", "fullBackupContent"):
    if app.get(android + name) != "false":
        raise SystemExit(f"Expected {name}=false")
if app.get(android + "debuggable", "false") != "false" or not app.get(android + "dataExtractionRules"):
    raise SystemExit("Release manifest must disable debugging and declare data extraction rules")

dex_out = subprocess.run([str(analyzer), "dex", "packages", str(args.apk)], check=True, stdout=subprocess.PIPE, text=True).stdout
forbidden_symbols = ("cn.yingxia.protocol", "KtorSyncBridge", "io.ktor", "okhttp3")
leaked = [sym for sym in forbidden_symbols if sym in dex_out]
if leaked:
    raise SystemExit("Forbidden sync/network packages found in offline APK DEX: " + ", ".join(leaked))

print("PASS: network permissions absent; zero sync/network DEX classes; backup disabled; extraction rules declared; debugging disabled.")
