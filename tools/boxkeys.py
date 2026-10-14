"""Phase 0: how does the app on the TV box react to remote keys?

Sends one key at a time and compares the active media session before and after
(state, position jump), then puts playback back where it was. Stdlib only.
    BOX=ip:port python3 tools/boxkeys.py [keys…]
"""
import os
import re
import subprocess
import sys
import time

BOX = os.environ.get("BOX") or sys.exit("set BOX=<tv-ip>:5555 (adb over the network)")
STATE = re.compile(r"state=PlaybackState \{state=(\w+)\((\d+)\), position=(-?\d+),.*?speed=(-?[\d.]+), "
                   r"updated=(\d+), actions=(\d+)")
PLAYING = "3"

TRIALS = [
    "KEYCODE_MEDIA_PAUSE", "KEYCODE_MEDIA_PLAY",
    "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_PLAY_PAUSE",
    "KEYCODE_MEDIA_FAST_FORWARD", "KEYCODE_MEDIA_REWIND",
    "dispatch fast-forward", "dispatch rewind",
    "KEYCODE_MEDIA_SKIP_FORWARD", "KEYCODE_MEDIA_SKIP_BACKWARD",
    "KEYCODE_MEDIA_STEP_FORWARD", "KEYCODE_MEDIA_STEP_BACKWARD",
    "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_LEFT",
]


def sh(cmd):
    return subprocess.run(["adb", "-s", BOX, "shell", cmd], capture_output=True, text=True, timeout=30).stdout


def active():
    """The most recent non-Bluetooth session that is playing (or paused)."""
    out = sh("cat /proc/uptime; dumpsys media_session")
    now = float(out.split()[0]) * 1000  # same clock as PlaybackState.updated
    seen, sessions, pkg = set(), [], None
    for line in out.splitlines():
        m = re.search(r"\bpackage=(\S+)", line)
        if m:
            pkg = m.group(1)
            continue
        m = STATE.search(line)
        if not m or not pkg or pkg == "com.android.bluetooth" or pkg in seen:
            continue
        seen.add(pkg)
        name, code, pos, speed, upd, actions = m.groups()
        pos = int(pos) + ((now - int(upd)) * float(speed) if code == PLAYING else 0)
        sessions.append({"pkg": pkg, "state": name, "playing": code == PLAYING, "pos": pos, "actions": int(actions)})
    for want in (True, False):
        for s in sessions:
            if s["playing"] == want and s["state"] in ("PLAYING", "PAUSED", "BUFFERING"):
                return s
    return None


def send(key):
    sh("cmd media_session " + key if key.startswith("dispatch") else "input keyevent " + key)


def top_app():
    m = re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys activity activities | grep -m1 topResumedActivity"))
    return m.group(1) if m else "?"


def main(keys):
    s = active()
    print(f"top: {top_app()}")
    if not s:
        sys.exit("no media session is playing — start a video first")
    print(f"session: {s['pkg']} {s['state']} at {s['pos'] / 1000:.0f}s, actions={s['actions']:#x}"
          f" (seekTo {'yes' if s['actions'] & 256 else 'no'})")
    for key in keys:
        b = active()
        t0 = time.time()
        send(key)
        time.sleep(2.5)
        a = active()
        if not a or not b:
            print(f"{key:30} session lost")
            continue
        dt = (time.time() - t0) * 1000
        jump = a["pos"] - b["pos"] - (dt if b["playing"] and a["playing"] else 0)
        note = "" if abs(jump) < 1500 else f"jump {jump / 1000:+.0f}s"
        print(f"{key:30} {b['state']:>9} → {a['state']:<9} {note}")
        if not a["playing"] and key != "KEYCODE_MEDIA_PAUSE" and "PLAY_PAUSE" not in key:
            send("KEYCODE_MEDIA_PLAY")  # back to playing for the next trial
        time.sleep(4)  # let the player's overlay hide again


if __name__ == "__main__":
    main(sys.argv[1:] or TRIALS)
