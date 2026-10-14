"""Score a phase-0 recording the way the app will listen: Vosk running non-stop
over the whole file with a grammar of "пульт <command>" phrases. Detections are
matched against what the tablet showed (the .tsv written next to the .wav).
    python3 -I tools/eval.py recordings/<name>.wav [--channel 0|1|mix] [--free]
"""
import argparse
import array
import json
import math
import os
import statistics
import wave

import vosk

WAKE = "пульт"
COMMANDS = ["пауза", "играй", "продолжи", "стоп", "громче", "тише", "вперёд", "назад",
            "назад на минуту", "вперёд на минуту", "выключи звук", "включи звук"]
MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "models", "vosk-model-small-ru-0.22")


def load(path, channel):
    with wave.open(path) as w:
        ch, rate = w.getnchannels(), w.getframerate()
        a = array.array("h", w.readframes(w.getnframes()))
    if ch == 1:
        return a, rate
    if channel == "mix":
        return array.array("h", ((a[i] + a[i + 1]) // 2 for i in range(0, len(a), 2))), rate
    return a[int(channel)::ch], rate


def script(tsv):
    """Items from the .tsv plus which half of the test each belongs to (TV paused / playing)."""
    items, tv = [], "?"
    for line in open(tsv, encoding="utf-8"):
        if line.startswith("#"):
            continue
        start, end, kind, text, mark = line.rstrip("\n").split("\t")
        if mark == "pause":
            tv = "тихо"
        elif mark == "play":
            tv = "ТВ"
        items.append({"start": int(start) / 1000, "end": int(end) / 1000, "kind": kind, "text": text, "tv": tv})
    return items


def phrase(text):
    return " ".join(text.lower().replace(",", " ").split())


GRAMMAR = None  # --grammar: the app's own grammar.json (pulled from the tablet)


def listen(model, pcm, rate):
    """Continuous grammar decoding → [(time, command, conf)] for every 'пульт …' heard."""
    grammar = GRAMMAR or [WAKE] + [f"{WAKE} {c}" for c in COMMANDS] + ["[unk]"]
    rec = vosk.KaldiRecognizer(model, rate, json.dumps(grammar, ensure_ascii=False))
    rec.SetWords(True)
    words = []
    data = pcm.tobytes()
    step = rate // 5 * 2  # 200 ms
    for i in range(0, len(data), step):
        if rec.AcceptWaveform(data[i:i + step]):
            words += json.loads(rec.Result()).get("result", [])
    words += json.loads(rec.FinalResult()).get("result", [])
    found, i = [], 0
    while i < len(words):
        if words[i]["word"] != WAKE:
            i += 1
            continue
        j, tail = i + 1, []
        while j < len(words) and words[j]["word"] not in (WAKE, "[unk]") and words[j]["start"] - words[j - 1]["end"] < 1.0:
            tail.append(words[j])
            j += 1
        conf = min(w["conf"] for w in [words[i]] + tail)
        found.append((words[i]["start"], " ".join(w["word"] for w in tail) or "—", conf))
        i = j
    return found


def free(model, pcm, rate, start, end):
    """What a grammar-free recognizer hears in a window — tells mishearing from silence."""
    rec = vosk.KaldiRecognizer(model, rate)
    rec.AcceptWaveform(pcm[int(start * rate):int(end * rate)].tobytes())
    return json.loads(rec.FinalResult()).get("text", "")


def level(pcm, rate, start, end):
    seg = pcm[int(start * rate):int(end * rate)]
    if not seg:
        return -90.0
    rms = math.sqrt(sum(v * v for v in seg) / len(seg))
    return 20 * math.log10(rms / 32768) if rms >= 1 else -90.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("--channel", default="mix")
    ap.add_argument("--free", action="store_true", help="also show grammar-free decoding per prompt")
    ap.add_argument("--slack", type=float, default=2.0, help="seconds a reply may run past its prompt")
    ap.add_argument("--grammar", help="grammar.json written by the app")
    args = ap.parse_args()
    global GRAMMAR
    if args.grammar:
        GRAMMAR = json.load(open(args.grammar, encoding="utf-8"))

    vosk.SetLogLevel(-1)
    model = vosk.Model(MODEL)
    pcm, rate = load(args.wav, args.channel)
    items = script(args.wav[:-4] + ".tsv")
    heard = listen(model, pcm, rate)
    says = [it for it in items if it["kind"] == "say"]

    def owner(t):
        """The prompt the speaker was answering: nobody starts talking within 0.5 s of a new prompt."""
        shown = [it for it in says if it["start"] <= t - 0.5]
        if shown and t < shown[-1]["end"] + args.slack:
            return shown[-1]
        return None

    used = set()
    print(f"{os.path.basename(args.wav)}  channel={args.channel}")
    stats = {}
    for it in says:
        want = phrase(it["text"]).split(" ", 1)[1]
        hits = [n for n, h in enumerate(heard) if owner(h[0]) is it]
        got = [heard[n][1] for n in hits]
        used.update(hits)
        ok = want in got
        s = stats.setdefault(it["tv"], {"ok": 0, "wrong": 0, "miss": 0})
        s["ok" if ok else "wrong" if got else "miss"] += 1
        line = f"  [{it['tv']:>4}] {want:<16} → {', '.join(got) or 'не услышал':<22} {'✓' if ok else '✗'}"
        line += f"  {level(pcm, rate, it['start'], it['end']):.0f} dBFS"
        if args.free:
            line += f"  «{free(model, pcm, rate, it['start'], it['end'] + args.slack)}»"
        print(line)

    for tv, s in stats.items():
        total = sum(s.values())
        print(f"{tv}: {s['ok']}/{total} верно, {s['wrong']} не та команда, {s['miss']} пропущено")
    stray = [h for n, h in enumerate(heard) if n not in used]
    tv_only = [it for it in items if it["kind"] == "info" and it["end"] - it["start"] >= 60]
    for it in tv_only:
        fa = [h for h in stray if it["start"] <= h[0] < it["end"]]
        print(f"ложные срабатывания за {it['end'] - it['start']:.0f} с одного ТВ: {len(fa)}"
              + "".join(f"\n    {h[0]:.1f}s «пульт {h[1]}» conf {h[2]:.2f}" for h in fa))
        print(f"уровень ТВ: {statistics.median(level(pcm, rate, t, t + 1) for t in range(int(it['start']), int(it['end']))):.0f} dBFS (медиана)")
    other = [h for h in stray if not any(it["start"] <= h[0] < it["end"] for it in tv_only)]
    if other:
        print("прочие лишние: " + ", ".join(f"{h[0]:.1f}s «{h[1]}»" for h in other))


if __name__ == "__main__":
    main()
