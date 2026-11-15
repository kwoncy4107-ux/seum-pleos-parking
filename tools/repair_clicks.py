# -*- coding: utf-8 -*-
"""Patch the short audio dropouts the emulator's sound output produces (~1 ms flat blocks = clicks).

Each glitch is found by its broadband energy above the TTS band (the voice is band-limited to ~11 kHz) and
replaced with the waveform one pitch period earlier, crossfaded at the edges (packet-loss concealment).

Usage: python -I tools/repair_clicks.py <in.wav> <out.wav>
"""
import sys, wave
import numpy as np
from scipy import signal

src, dst = sys.argv[1], sys.argv[2]
with wave.open(src) as w:
    ch, sr = w.getnchannels(), w.getframerate()
    x = np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(float).reshape(-1, ch) / 32768
F = sr // 1000                                       # 1 ms frames
b, a = signal.butter(6, 12500 / (sr / 2), "high")


def one_pass(y):
    mono = y.mean(axis=1)
    h = np.abs(signal.filtfilt(b, a, mono))
    n = len(mono) // F * F
    hf = h[:n].reshape(-1, F).max(axis=1)
    lv = np.abs(mono[:n]).reshape(-1, F).max(axis=1)
    lvs = np.convolve(lv, np.ones(50) / 50, "same")
    bad = (hf > 0.03 * lvs + 0.0004) & (lvs > 0.002)   # nothing real lives above 12.5 kHz in TTS audio
    bad = np.convolve(bad.astype(int), np.ones(3, int), "same") > 0  # pad by a frame each side
    y = y.copy(); fixed = 0; i = 0
    up = np.linspace(0, 1, F)[:, None]
    while i < len(bad):
        if not bad[i]:
            i += 1; continue
        j = i
        while j < len(bad) and bad[j]: j += 1
        s, e = i * F, j * F
        L, W = e - s, int(0.006 * sr)
        if s - W - int(0.015 * sr) > 0 and e + F < len(y):
            ref = mono[s - W:s]
            best, bp = -2, int(0.005 * sr)
            for p in range(int(0.0025 * sr), int(0.012 * sr)):
                c = mono[s - W - p:s - p]
                r = np.dot(ref, c) / (np.linalg.norm(ref) * np.linalg.norm(c) + 1e-9)
                if r > best: best, bp = r, p
            fill = np.array([y[s - bp + (k % bp)] for k in range(L + F)])   # continue the last period
            head = y[s:s + F].copy()
            y[s:e] = fill[:L]
            y[s:s + F] = head * (1 - up) + fill[:F] * up                    # fade in from the real audio
            y[e:e + F] = fill[L:L + F] * (1 - up) + y[e:e + F] * up        # fade back out
            fixed += 1
        i = j
    return y, fixed


y, total = x, 0
for _ in range(4):
    y, k = one_pass(y); total += k
    if k == 0: break
fixed = total
pcm = (np.clip(y, -1, 1) * 32767).astype("<i2")
with wave.open(dst, "wb") as w:
    w.setnchannels(ch); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())
print("patched", fixed, "glitches ->", dst)
