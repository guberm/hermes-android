"""Signed-in device with a visible message: python scripts/check_message_ui.py SERIAL MESSAGE_TEXT."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(['adb', '-s', sys.argv[1], *args], text=True, encoding='utf-8')


def nodes():
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/hermes-message-check.xml')
    assert 'dumped to' in result, 'Fresh capture required'
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/hermes-message-check.xml')).iter('node'))


def find(label):
    return next(n for n in nodes() if n.get('text') == label or n.get('content-desc') == label)


def tap(node, hold=False):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    adb('shell', 'input', *(['swipe', x, y, x, y, '800'] if hold else ['tap', x, y]))


def top_inset():
    windows = adb('shell', 'dumpsys', 'window')
    bottom = max(map(int, re.findall(r'type=statusBars frame=\[0,0\]\[\d+,(\d+)\] visible=true', windows)))
    content = [n for n in nodes() if n.get('package') == 'dev.guber.hermesandroid' and (n.get('text') or n.get('content-desc'))]
    assert content
    assert all(int(re.findall(r'\d+', n.get('bounds'))[1]) >= bottom for n in content), 'Content overlaps status bar'
    print(f'PASS: content below status bar ({bottom}px)')


assert not any(n.get('text') == 'Copy message' or n.get('content-desc') == 'Copy message' for n in nodes())
tap(find(sys.argv[2]))
assert not any(n.get('text') == 'Copy message' for n in nodes()), 'Short tap must not open Copy'
tap(find(sys.argv[2]), hold=True)
tap(find('Copy message'))
assert not any(n.get('text') == 'Copy message' for n in nodes())
print('PASS: Copy menu only on long press')
top_inset()
tap(find('Connection settings'))
switch = next(n for n in nodes() if n.get('content-desc') == 'Dark mode' and n.get('checkable') == 'true')
original = switch.get('checked')
tap(switch)
assert next(n for n in nodes() if n.get('checkable') == 'true').get('checked') != original
adb('shell', 'am', 'force-stop', 'dev.guber.hermesandroid')
adb('shell', 'am', 'start', '-n', 'dev.guber.hermesandroid/.MainActivity')
time.sleep(1)
tap(find('Connection settings'))
switch = next(n for n in nodes() if n.get('content-desc') == 'Dark mode' and n.get('checkable') == 'true')
assert switch.get('checked') != original, 'Theme must survive restart'
tap(switch)
adb('shell', 'input', 'keyevent', '4')
print('PASS: Dark/Light toggle persists across restart; restored initial theme')
