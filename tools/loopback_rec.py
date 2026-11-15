# -*- coding: utf-8 -*-
"""Record what the PC's default speaker plays (WASAPI loopback) to a 48 kHz stereo WAV.

Usage: python -I tools/loopback_rec.py <out.wav> <seconds>
Used by record_scene.sh for the Pleos voice; the virtual DirectShow capture device added clicks.
"""
import sys, time, wave
import numpy as np
import soundcard as sc

out, secs = sys.argv[1], float(sys.argv[2])
SR, BLOCK = 48000, 4800
spk = sc.default_speaker()
mic = sc.get_microphone(id=str(spk.name), include_loopback=True)
chunks, total = [], int(SR * secs)
with mic.recorder(samplerate=SR, channels=2, blocksize=BLOCK) as r:
    got = 0
    open(out + ".start", "w").write(repr(time.time()))   # when sound capture really began
    while got < total:
        d = r.record(numframes=BLOCK)
        chunks.append(d); got += len(d)
x = np.concatenate(chunks)[:total]
pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
with wave.open(out, "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print("recorded", out, round(len(x) / SR, 2), "s")
