"""Checks that every word of the phrases in Commands.java exists in the Vosk model.

A word missing from the vocabulary is silently dropped from the grammar and the
phrase then never matches, so run this after touching Commands.java:
    .venv/bin/python -I tools/vocab-check.py
Vosk reports missing words only as warnings on stderr (from native code), hence
the child process.
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
MODEL = os.path.join(ROOT, "models", "vosk-model-small-ru-0.22")

if sys.argv[1:] == ["--child"]:
    import vosk

    vosk.SetLogLevel(0)
    words = json.loads(sys.stdin.read())
    vosk.KaldiRecognizer(vosk.Model(MODEL), 16000, json.dumps(words + ["[unk]"], ensure_ascii=False))
    sys.exit(0)

src = open(os.path.join(ROOT, "src/com/megarohas/tvvoice/Commands.java"), encoding="utf-8").read()
words = sorted({w for s in re.findall(r'"([а-яё ]+)"', src) for w in s.split()})  # lowercase literals = phrases
child = subprocess.run([sys.executable, "-I", os.path.abspath(__file__), "--child"],
                       input=json.dumps(words), capture_output=True, text=True)
missing = re.findall(r"missing in vocabulary: '([^']+)'", child.stderr)
print(f"{len(words)} words, missing: {', '.join(missing) if missing else 'none'}")
sys.exit(1 if missing or child.returncode else 0)
