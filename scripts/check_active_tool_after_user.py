"""Assert user message, work card, then assistant answer are in visual order."""
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
reply = next((node for node in nodes if node.get("text") == sys.argv[3]), None)
assert user is not None, "User message is not visible"
assert tool is not None, "Active tool must be visible after the user message"
assert reply is not None, "Assistant answer is not visible"
user_top = int(re.findall(r"\d+", user.get("bounds"))[1])
tool_top = int(re.findall(r"\d+", tool.get("bounds"))[1])
reply_top = int(re.findall(r"\d+", reply.get("bounds"))[1])
assert user_top < tool_top < reply_top, "Expected user message, work card, then assistant answer"
print(f"PASS: user={user_top}px tool={tool_top}px answer={reply_top}px")
