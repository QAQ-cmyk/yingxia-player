"""Inspect the release manifest after Android library manifests have been merged."""
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
        parser.error("Set ANDROID_HOME or pass --apkanalyzer")
    name = "apkanalyzer.bat" if os.name == "nt" else "apkanalyzer"
    analyzer = Path(sdk) / "cmdline-tools" / "latest" / "bin" / name

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
print("PASS: network permissions absent; backup disabled; extraction rules declared; debugging disabled.")
