"""Assert that Hermes has no foreground waiting service or ongoing waiting notification."""
import subprocess
import sys


def adb(*args):
    return subprocess.check_output(["adb", "-s", sys.argv[1], *args], text=True, encoding="utf-8")


services = adb("shell", "dumpsys", "activity", "services", "dev.guber.hermesandroid")
notifications = adb("shell", "dumpsys", "notification", "--noredact")
assert "ReplyWaitService" not in services
assert "Hermes is working" not in notifications
print("PASS: no Hermes foreground waiting service or persistent waiting notification")
