#!/usr/bin/env python3
"""Phone screenshots for Google Play and F-Droid from an Android emulator (see README.md).

Drives the installed app with adb and uiautomator against the dev stack of bike-maintainer-deploy (example vehicles
from the portal's screenshot tool) and writes fastlane/metadata/android/<locale>/images/phoneScreenshots/1.png … 6.png.
The display is set to 1080×2160 for the run, because Play accepts at most 2:1. The system language is switched with
root rights, so use an emulator image without Google Play ("default" or "google_apis").

Usage: screenshots.py   (environment: BM_SHOT_EMAIL, BM_SHOT_PASSWORD, optional ADB, BM_SHOT_APP_SERVER)
"""

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
PACKAGE = "de.bikemaintainer.app"
# The emulator reaches the host as 10.0.2.2; the dev stack publishes the app on port 8100.
SERVER = os.environ.get("BM_SHOT_APP_SERVER", "http://10.0.2.2:8100")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "..", "..")
SEEDER = os.path.join(REPO, "..", "bike-maintainer-portal", "tools", "screenshots")
LOCALES = {"de": ("de-DE", "de-DE"), "en": ("en-GB", "en-US")}  # language: (system locale, fastlane folder)


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/bm-ui.xml")
    return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/bm-ui.xml")).iter("node"))


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(pattern, timeout=20):
    """First node whose text or content description matches the regular expression; waits until it appears."""
    regex = re.compile(pattern)
    end = time.time() + timeout
    while time.time() < end:
        for node in nodes():
            if regex.search(node.get("text", "")) or regex.search(node.get("content-desc", "")):
                return node
        time.sleep(1)
    raise SystemExit(f"Not on screen: {pattern}")


def tap(pattern, timeout=20):
    x, y = center(find(pattern, timeout))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.5)


def fill(node, text):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb("shell", "input", "keyevent", "--longpress", *["KEYCODE_DEL"] * 60)
    adb("shell", "input", "text", text.replace(" ", "%s"))


def edits():
    return [n for n in nodes() if n.get("class") == "android.widget.EditText"]


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)


def set_locale(locale):
    adb("root")
    adb("wait-for-device")
    adb("shell", "setprop", "persist.sys.locale", locale)
    adb("shell", "setprop", "ctl.restart", "zygote")
    time.sleep(5)
    adb("wait-for-device")
    while adb("shell", "getprop", "sys.boot_completed", check=False).strip() != "1":
        time.sleep(2)
    time.sleep(5)


def night(on):
    adb("shell", "cmd", "uimode", "night", "yes" if on else "no")
    time.sleep(2.5)


def demo_status_bar():
    """Fixed clock, full battery and signal, no notification icons (Android's system UI demo mode)."""
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    demo = ["shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
    adb(*demo, "enter")
    adb(*demo, "clock", "-e", "hhmm", "1000")
    adb(*demo, "battery", "-e", "level", "100", "-e", "plugged", "false")
    adb(*demo, "network", "-e", "wifi", "show", "-e", "level", "4")
    adb(*demo, "network", "-e", "mobile", "show", "-e", "level", "4", "-e", "datatype", "none")
    adb(*demo, "notifications", "-e", "visible", "false")


def screenshot(path):
    time.sleep(1.5)
    with open(path, "wb") as file:
        file.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], check=True, capture_output=True).stdout)
    print(f"  {os.path.relpath(path, REPO)}")


def main():
    email, password = os.environ["BM_SHOT_EMAIL"], os.environ["BM_SHOT_PASSWORD"]
    adb("shell", "wm", "size", "1080x2160")
    try:
        for lang, (locale, folder) in LOCALES.items():
            print(f"{lang}:")
            out = os.path.join(REPO, "fastlane", "metadata", "android", folder, "images", "phoneScreenshots")
            os.makedirs(out, exist_ok=True)
            # The app shows the server's data: example vehicles in this language first.
            subprocess.run(["node", "shoot.mjs", "--seed", lang], cwd=SEEDER, check=True)
            set_locale(locale)
            demo_status_bar()
            adb("shell", "pm", "clear", PACKAGE)
            adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
            night(False)
            adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
            find(r"^(Own server|Eigener Server)$")
            screenshot(os.path.join(out, "6.png"))

            tap(r"^(Own server|Eigener Server)$")
            fill(edits()[0], SERVER)
            tap(r"^(Continue|Weiter)$")
            # Plain HTTP to the dev stack: the app warns once.
            tap(r"^(Connect anyway|Trotzdem verbinden)$")
            fields = edits()
            fill(fields[0], email)
            fill(fields[1], password)
            fill(edits()[2], "Pixel")
            back()  # closes the keyboard
            tap(r"^(Sign in|Log in|Anmelden)$")
            # First start: the app asks for reminders (the permission is already granted above).
            tap(r"^(Allow|Erlauben)$", timeout=30)
            find(r"^MT-07$", timeout=30)
            screenshot(os.path.join(out, "5.png"))

            night(True)
            find(r"^MT-07$")
            screenshot(os.path.join(out, "1.png"))
            tap(r"^MT-07$")
            find(r"(Log maintenance|Wartung erfassen)$")
            screenshot(os.path.join(out, "2.png"))
            tap(r"^(Log maintenance|Wartung erfassen)$")
            screenshot(os.path.join(out, "3.png"))
            back()
            # Down to the latest measurements (chain and tyres).
            for _ in range(2):
                adb("shell", "input", "swipe", "540", "1800", "540", "500", "400")
                time.sleep(1.2)
            screenshot(os.path.join(out, "4.png"))
            back()
    finally:
        adb("shell", "wm", "size", "reset", check=False)


if __name__ == "__main__":
    main()
