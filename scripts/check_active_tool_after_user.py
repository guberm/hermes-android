"""Assert that an active tool card is visible after the specified user message."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(["adb", "-s", sys.argv[1], *args], text=True, encoding="utf-8")


result = adb("shell", "uiautomator", "dump", "/sdcard/hermes-order-check.xml")
assert "dumped to" in result, "Fresh UI capture required"
nodes = list(ET.fromstring(adb("shell", "cat", "/sdcard/hermes-order-check.xml")).iter("node"))
user = next((node for node in nodes if node.get("text") == sys.argv[2]), None)
tool = next((node for node in nodes if node.get("text") == "Thinking"), None)
assert user is not None, "User message is not visible"
assert tool is not None, "Active tool must be visible after the user message"
user_top = int(re.findall(r"\d+", user.get("bounds"))[1])
tool_top = int(re.findall(r"\d+", tool.get("bounds"))[1])
assert tool_top > user_top, "Active tool appears before the user message"
print(f"PASS: active tool at {tool_top}px follows user message at {user_top}px")
