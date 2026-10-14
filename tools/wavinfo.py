"""Per-channel level and inter-channel correlation of a 16-bit WAV (stdlib only)."""
import array, math, sys, wave

for path in sys.argv[1:]:
    with wave.open(path) as w:
        ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
        a = array.array("h", w.readframes(n))
    chans = [a[i::ch] for i in range(ch)]
    def db(x):
        r = math.sqrt(sum(v * v for v in x) / max(1, len(x)))
        return 20 * math.log10(r / 32768) if r >= 1 else -90.0
    info = ", ".join(f"ch{i} {db(c):.1f} dBFS" for i, c in enumerate(chans))
    corr = ""
    if ch == 2:
        x, y = chans
        mx, my = sum(x) / len(x), sum(y) / len(y)
        sxy = sum((p - mx) * (q - my) for p, q in zip(x, y))
        sxx = sum((p - mx) ** 2 for p in x); syy = sum((q - my) ** 2 for q in y)
        same = sum(1 for p, q in zip(x, y) if p == q) / len(x)
        corr = f", corr {sxy / math.sqrt(sxx * syy + 1e-9):.3f}, identical samples {same:.0%}"
    print(f"{path}: {n / rate:.1f}s {rate} Hz {ch}ch — {info}{corr}")
