#!/usr/bin/env python3
"""Lightweight ADB smoke test for ZeroVox catalogues.

Opens the app, walks every catalogue, reports All/PS4/PS5 counts, and
optionally starts a download for one PS4 and one PS5 game, then cancels it
after a few seconds so the test stays small and fast.

Usage:
  python3 tools/adb_catalog_check.py
  python3 tools/adb_catalog_check.py --download
  python3 tools/adb_catalog_check.py --serial 127.0.0.1:5555 --out /tmp/opencode/adb_check
"""
import argparse
import os
import re
import subprocess
import sys
import time

CATALOGS = ["Orbit", "DLPSGame", "Pippo", "PFS", "Zer0", "evoX", "Blackbox"]
PKG = "com.zerovox"
ACT = "com.zerovox/.MainActivity"


class Adb:
    def __init__(self, serial=None):
        self.serial = serial
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args, timeout=30):
        p = subprocess.run(self.base + list(args), capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout, p.stderr

    def shell(self, *args, timeout=30):
        return self.run("shell", *args, timeout=timeout)[1]

    def dump(self):
        for _ in range(3):
            self.shell("uiautomator", "dump", "/sdcard/ui.xml")
            rc, out, _ = self.run("shell", "cat", "/sdcard/ui.xml")
            if rc == 0 and "<hierarchy" in out:
                return out
            time.sleep(0.3)
        return ""

    def tap(self, x, y):
        self.shell("input", "tap", str(int(x)), str(int(y)))

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.shell("input", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(int(ms)))

    def screenshot(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            subprocess.run(self.base + ["exec-out", "screencap", "-p"], stdout=f, timeout=30)


def nodes(xml):
    pat = re.compile(r'text="([^"]*)"[^>]*resource-id="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    out = []
    for m in pat.finditer(xml):
        text, rid, x1, y1, x2, y2 = m.groups()
        out.append({"text": text, "id": rid, "bounds": tuple(map(int, (x1, y1, x2, y2)))})
    return out


def find(ns, text, exact=True):
    for n in ns:
        if (n["text"] == text) if exact else (text in n["text"]):
            return n
    return None


def center(n, screen_w=1080, screen_h=2400):
    x1, y1, x2, y2 = n["bounds"]
    x1, x2 = max(0, x1), min(screen_w, x2)
    y1, y2 = max(0, y1), min(screen_h, y2)
    return (x1 + x2) // 2, (y1 + y2) // 2


def wait_for(adb, text, exact=True, timeout=8.0):
    end = time.time() + timeout
    while time.time() < end:
        ns = nodes(adb.dump())
        n = find(ns, text, exact)
        if n:
            return n, ns
        time.sleep(0.35)
    return None, nodes(adb.dump())


def launch(adb):
    adb.shell("am", "force-stop", PKG)
    adb.shell("am", "start", "-n", ACT)
    time.sleep(1.0)
    n, _ = wait_for(adb, "Games", timeout=10)
    if not n:
        adb.shell("am", "start", "-n", ACT)
        time.sleep(1.0)
        n, _ = wait_for(adb, "Games", timeout=8)
    if not n:
        raise SystemExit("app did not reach the Games tab")
    adb.tap(*center(n))
    time.sleep(0.5)


def scroll_chips_to_start(adb):
    for _ in range(4):
        adb.swipe(150, 460, 950, 460, 250)
        time.sleep(0.15)


def goto_games(adb):
    n, _ = wait_for(adb, "Games", timeout=4)
    if n:
        adb.tap(*center(n))
        time.sleep(0.4)
    return n is not None


def select_catalog(adb, name):
    scroll_chips_to_start(adb)
    for _ in range(8):
        ns = nodes(adb.dump())
        n = find(ns, name)
        if n and n["bounds"][0] >= 0 and n["bounds"][2] <= 1080:
            adb.tap(*center(n))
            time.sleep(0.8)
            return True
        adb.swipe(950, 460, 150, 460, 300)
        time.sleep(0.2)
    return False


def select_filter(adb, name):
    n, _ = wait_for(adb, name, timeout=4)
    if not n:
        return False
    adb.tap(*center(n))
    time.sleep(0.5)
    return True


def read_count(adb):
    ns = nodes(adb.dump())
    for n in ns:
        m = re.match(r"(\d+)\s+of\s+(\d+)\s+games", n["text"])
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def read_games(adb, limit=3):
    ns = nodes(adb.dump())
    out = []
    for n in ns:
        t = n["text"]
        if re.match(r"^[A-Z0-9]{4}\d{5}", t) or t in ("PS4", "PS5"):
            continue
        if t and n["bounds"][1] > 700 and n["bounds"][1] < 2130 and len(t) > 2:
            if t not in out and not t.startswith("View options") and "games" not in t:
                out.append(t)
        if len(out) >= limit:
            break
    return out


def test_download(adb, label, outdir):
    n, ns = wait_for(adb, "View options →", exact=False, timeout=4)
    if not n:
        return "no option button"
    adb.tap(*center(n))
    time.sleep(0.7)
    ns = nodes(adb.dump())
    phone = find(ns, "Phone")
    if not phone:
        # close the sheet if it opened differently
        close = find(ns, "Close")
        if close:
            adb.tap(*center(close))
        return "no Phone button"
    adb.tap(*center(phone))
    time.sleep(2.0)
    ns = nodes(adb.dump())
    # Link-lock pages (vikingfile, akirabox) need their Download button tapped.
    if not find(ns, "Cancel download"):
        dl = find(ns, "Download")
        if dl:
            adb.tap(*center(dl))
            time.sleep(1.0)
    cancel, ns = wait_for(adb, "Cancel download", timeout=20)
    if not cancel:
        ns = nodes(adb.dump())
    for _ in range(12):
        if any("Downloading" in x["text"] for x in ns):
            break
        time.sleep(0.5)
        ns = nodes(adb.dump())
    time.sleep(2.0)
    ns = nodes(adb.dump())
    status = ""
    for x in ns:
        if "Downloading" in x["text"] or "Preparing" in x["text"] or "Cancelled" in x["text"]:
            status = x["text"]
            break
    progress = ""
    for x in ns:
        if re.search(r"\d+(\.\d+)?\s*(MB|GB)\s*/", x["text"]) or "0 B" in x["text"]:
            progress = x["text"]
            break
    adb.screenshot(os.path.join(outdir, f"{label}-download.png"))
    cancel = find(ns, "Cancel download")
    if cancel:
        adb.tap(*center(cancel))
        time.sleep(0.6)
    return f"{status} | {progress}".strip(" |")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--out", default="/tmp/opencode/adb_check")
    ap.add_argument("--download", action="store_true", help="start and cancel one PS4 and one PS5 download")
    ap.add_argument("--catalogs", default=",".join(CATALOGS), help="comma separated catalogue names")
    ap.add_argument("--no-screenshots", action="store_true")
    args = ap.parse_args()
    wanted = [c.strip() for c in args.catalogs.split(",") if c.strip()]

    adb = Adb(args.serial)
    rc, out, err = adb.run("devices")
    if "device" not in out:
        raise SystemExit("no adb device: " + (err or out))

    os.makedirs(args.out, exist_ok=True)
    launch(adb)
    adb.screenshot(os.path.join(args.out, "catalog-start.png"))

    report = []
    for cat in wanted:
        print(f"[{cat}] selecting", flush=True)
        if not select_catalog(adb, cat):
            report.append((cat, "not found", None, None, None, []))
            print(f"[{cat}] chip not found", flush=True)
            continue
        select_filter(adb, "All")
        all_count = read_count(adb)
        print(f"[{cat}] all={all_count}", flush=True)
        select_filter(adb, "PS4")
        ps4_count = read_count(adb)
        ps4_games = read_games(adb)
        print(f"[{cat}] ps4={ps4_count}", flush=True)
        select_filter(adb, "PS5")
        ps5_count = read_count(adb)
        print(f"[{cat}] ps5={ps5_count}", flush=True)
        select_filter(adb, "All")
        games = read_games(adb)
        if not args.no_screenshots:
            adb.screenshot(os.path.join(args.out, f"{cat.lower()}-all.png"))
        report.append((cat, all_count, ps4_count, ps5_count, games, ps4_games))
        print(f"{cat:9} all={all_count} ps4={ps4_count} ps5={ps5_count} first={games[:2]}", flush=True)

    if args.download:
        print("\ndownload smoke test (started, then cancelled)", flush=True)
        for cat, plat in (("Blackbox", "PS4"), ("Blackbox", "PS5")):
            goto_games(adb)
            select_catalog(adb, cat)
            select_filter(adb, plat)
            result = test_download(adb, f"{cat.lower()}-{plat.lower()}", args.out)
            print(f"{cat} {plat}: {result}", flush=True)
            select_filter(adb, "All")

    print("\nsummary")
    for cat, all_count, ps4_count, ps5_count, games, ps4_games in report:
        print(f"- {cat}: all={all_count} ps4={ps4_count} ps5={ps5_count}")
    print("screenshots:", args.out)


if __name__ == "__main__":
    main()
