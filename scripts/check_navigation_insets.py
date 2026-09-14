"""Run on an open chat: python scripts/check_navigation_insets.py ADB_SERIAL."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(["adb", "-s", sys.argv[1], *args], text=True, encoding="utf-8")

result = adb("shell", "uiautomator", "dump", "/sdcard/hermes-insets-check.xml")
assert "dumped to" in result, "Fresh UI capture required"
nodes = list(ET.fromstring(adb("shell", "cat", "/sdcard/hermes-insets-check.xml")).iter("node"))
footer = next(n for n in nodes if n.get("text", "").startswith("Files are staged"))
bottom = int(re.findall(r"\d+", footer.get("bounds"))[-1])
windows = adb("shell", "dumpsys", "window")
frames = re.findall(r"type=navigationBars frame=\[\d+,(\d+)\]\[\d+,(\d+)\] visible=true", windows)
bar_tops = [int(top) for top, end in frames if 0 < int(top) < int(end)]
assert bar_tops, "Visible bottom navigation bar required"
assert bottom <= min(bar_tops), f"Composer bottom {bottom} overlaps navigation bar at {min(bar_tops)}"
keyboard_tops = [int(top) for top in re.findall(r"type=ime frame=\[\d+,(\d+)\]\[\d+,\d+\](?: visibleFrame=\[\d+,\d+\]\[\d+,\d+\])? visible=true", windows) if int(top) > 0]
if "--keyboard" in sys.argv:
    assert keyboard_tops, "Keyboard must be visible for this check"
if keyboard_tops:
    assert bottom <= min(keyboard_tops), "Composer overlaps the keyboard"
    assert min(keyboard_tops) - bottom < 150, f"Composer is too far above keyboard: {min(keyboard_tops) - bottom}px"
print(f"PASS: composer bottom={bottom}, navigation bar top={min(bar_tops)}, keyboard top={min(keyboard_tops) if keyboard_tops else 'hidden'}")
