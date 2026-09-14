"""Device regression: python scripts/check_drawer.py ADB_SERIAL (signed-in app)."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(["adb", "-s", sys.argv[1], *args], text=True, encoding="utf-8")


def nodes():
    for _ in range(4):
        result = adb("shell", "uiautomator", "dump", "/sdcard/hermes-drawer-check.xml")
        if "dumped to" in result:
            return list(ET.fromstring(adb("shell", "cat", "/sdcard/hermes-drawer-check.xml")).iter("node"))
        time.sleep(1)
    raise AssertionError("Could not capture fresh UI")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.5)


def find(description):
    return next((n for n in nodes() if n.get("content-desc") == description), None)


adb("shell", "am", "start", "-n", "dev.guber.hermesandroid/.MainActivity")
time.sleep(1)
menu = find("Sessions")
if menu is not None:
    tap(menu)
close = find("Close sessions")
assert close is not None, "Drawer needs an explicit Close sessions button"
search = next(n for n in nodes() if n.get("class") == "android.widget.EditText")
tap(search)
tap(find("Close sessions"))
assert find("Sessions") is not None, "Close must return to chat while search is focused"
tap(find("Sessions"))
adb("shell", "input", "keyevent", "4")
time.sleep(0.5)
assert find("Sessions") is not None, "System Back must close the drawer"
print("PASS: explicit close with focused search; system Back closes drawer")
