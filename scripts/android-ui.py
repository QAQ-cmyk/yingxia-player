"""Small adb UI helper for the isolated emulator; only acts on the selected serial."""
import argparse
import os
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
ROOT = pathlib.Path(__file__).resolve().parents[1]
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk:
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("sdk.dir="):
            sdk = line.split("=", 1)[1].replace("\\\\", "\\").replace("\\:", ":")
ADB = str(pathlib.Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))

class Device:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args):
        return subprocess.check_output([ADB, "-s", self.serial, *args], timeout=30)

    def nodes(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/yingxia-ui.xml")
        raw = self.adb("shell", "cat", "/sdcard/yingxia-ui.xml")
        return list(ET.fromstring(raw).iter("node"))

    def tap(self, label, occurrence=0):
        nodes = [n for n in self.nodes() if label in (n.get("text"), n.get("content-desc"), n.get("resource-id"))]
        if len(nodes) <= occurrence:
            raise AssertionError(f"UI element not found: {label}")
        bounds = [int(x) for x in re.findall(r"\d+", nodes[occurrence].get("bounds"))]
        self.adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        time.sleep(.4)

    def screenshot(self, path):
        path = pathlib.Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self.adb("exec-out", "screencap", "-p"))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("action", choices=["dump", "tap", "shot"])
    parser.add_argument("value", nargs="?")
    parser.add_argument("--occurrence", type=int, default=0)
    args = parser.parse_args()
    device = Device(args.serial)
    if args.action == "dump":
        for node in device.nodes():
            if node.get("text") or node.get("content-desc"):
                print(node.get("text"), "|", node.get("content-desc"), "|", node.get("bounds"))
    elif args.action == "tap":
        device.tap(args.value, args.occurrence)
    else:
        device.screenshot(args.value)
