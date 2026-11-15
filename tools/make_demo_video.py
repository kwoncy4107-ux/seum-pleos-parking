# -*- coding: utf-8 -*-
"""Cut the two recorded scenes (tools/record_scene.sh) into the contest demo video.

Usage: python -I tools/make_demo_video.py <raw_dir> <out.mp4>
Each scene: emulator screen (1920x1080) + PC sound (Pleos voice), with a caption strip added below the
screen so nothing in the car UI is covered. Title cards between scenes.
"""
import os, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

RAW, OUT = sys.argv[1], sys.argv[2]
W, H, STRIP = 1920, 1080, 150
FB = "C:/Windows/Fonts/malgunbd.ttf"
FR = "C:/Windows/Fonts/malgun.ttf"
tmp = tempfile.mkdtemp(prefix="seumvid_")

# (start, end, caption, sub) in scene-video seconds
SCENES = [
    ("check", 0.0, 29.0, [
        (0, 10, "① 세우기 전 — 주행 중에는 '이 자리 확인' 버튼이 잠겨 있어요 (세움 앱을 열었을 때 화면)", "주행 중 화면은 큰 버튼 하나와 한 줄만 보여 줍니다"),
        (10, 16, "빈자리를 보고 D단 그대로 브레이크만 밟아 정차 → 버튼 활성화", "서울 강북구 수유 시장 골목"),
        (16, 29, "'이 자리 확인'을 누르면 현재 정차한 곳에 주차해도 괜찮은지 화면과 음성으로 알려줘요", "횡단보도까지 1.4m · 금지 범위 10m 안 · 150m 앞 공영주차장 안내 (출발하면 다시 버튼 화면으로)"),
    ]),
    ("park", 4.0, 46.6, [   # first 4 s of steady 30 km/h driving cut
        (0, 12, "② 확인 없이 P단으로 차를 세웠을 때 — 세움 앱을 따로 켜지 않아도 돼요", "서울 종로구 익선동"),
        (12, 16.5, "확인 없이 정차하고 기어를 P에 넣으면", ""),
        (16.5, 34.5, "내비 화면 위로 자동 음성 경고 — 앱을 열 필요가 없어요", "\"횡단보도 6미터 앞이라 바로 신고될 수 있어요. 과태료 4만 원 …\""),
        (34.5, 43.3, "하단 세움 아이콘[P]을 누르면 자세한 내용을 볼 수 있어요", "왜 안 되는지 · 금지 범위 · 작년 단속 지점과 시간대"),
        (43.3, 46.6, "'주차장으로 안내'를 누르거나 \"응\"이라고 답하면 → 근처 주차장으로 내비게이션 안내", "300m 앞 종묘 공영주차장이 내비게이션 목적지로 바로 전달됩니다"),
    ]),
]
HOLD = {"park": 45.5}
CARDS = {
    "intro": ("세움", "세우기 전엔 버튼으로 묻고, 그냥 세워도 P에서 알려 드려요", "Pleos Connect 주차 보호 앱 · 에뮬레이터 실제 동작 · 실제 Pleos 음성"),
    "mid": ("모르고 그냥 세웠다면?", "기어를 P에 넣는 순간 차량이 먼저 알려 줍니다", ""),
    "end": ("세움", "세우기 전엔 묻고, 세우면 알려 줍니다", "github.com/kwoncy4107-ux/seum-pleos-parking"),
}


def run(args):
    subprocess.run(args, check=True)


BLUE, GREEN, INK, GREY = (31, 111, 235), (126, 231, 135), (17, 17, 17), (120, 120, 120)
FPS = 30


def icon(size):
    """The app icon (res/drawable/ic_seum.xml): blue rounded square, white P, green check."""
    k = 8; S = 108 * k
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rounded_rectangle([14 * k, 14 * k, 94 * k, 94 * k], 10 * k, fill=BLUE)
    d.rectangle([36 * k, 30 * k, 46 * k, 80 * k], fill="white")              # stem
    d.rectangle([36 * k, 30 * k, 56 * k, 62 * k], fill="white")
    d.ellipse([40 * k, 30 * k, 72 * k, 62 * k], fill="white")                # bowl
    d.rectangle([46 * k, 40 * k, 56 * k, 52 * k], fill=BLUE)
    d.ellipse([50 * k, 40 * k, 62 * k, 52 * k], fill=BLUE)                   # counter
    d.line([60 * k, 70 * k, 68 * k, 78 * k, 82 * k, 62 * k], fill=GREEN, width=7 * k, joint="curve")
    for x, y in ((60, 70), (82, 62)):
        d.ellipse([(x - 3.5) * k, (y - 3.5) * k, (x + 3.5) * k, (y + 3.5) * k], fill=GREEN)
    return im.resize((size, size), Image.LANCZOS)


def text_layer(text, font, col):
    f = ImageFont.truetype(font[0], font[1])
    l, t, r, b = f.getbbox(text)
    im = Image.new("RGBA", (r - l + 8, b - t + 16), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((4 - l, 8 - t), text, font=f, fill=col)
    return im


def ease(x):
    x = min(max(x, 0.0), 1.0); return 1 - (1 - x) ** 3


def gears(active, glow):
    """Pleos-style 'P R N D' with the active gear boxed; glow 0..1 turns the P box blue."""
    f = ImageFont.truetype(FB, 72); cell = 110
    im = Image.new("RGBA", (cell * 4, cell), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    for n, g in enumerate("PRND"):
        x = n * cell
        if g == active:
            col = tuple(int(INK[c] + (BLUE[c] - INK[c]) * glow) for c in range(3)) if g == "P" else INK
            d.rounded_rectangle([x + 8, 6, x + cell - 8, cell - 6], 12, fill=col)
            fc = "white"
        else:
            fc = (190, 190, 190)
        w = d.textlength(g, font=f); d.text((x + (cell - w) / 2, 10), g, font=f, fill=fc)
    return im


def card(name, sec=3.0):
    """Title card with light motion: elements rise and fade in one after another, a blue rule grows,
    and the whole card fades to black at the end. The mid card shifts the gear from D to P."""
    t, s, f = CARDS[name]
    HH = H + STRIP
    DY = 130   # centre the block on the full 1230 px frame (video + caption strip)
    els = []   # (layer, centre-y, appear-time)
    if name != "mid":
        els.append((icon(150), 230, 0.0))
    els.append((text_layer(t, (FB, 120 if name != "mid" else 96), INK), 450 if name != "mid" else 330, 0.15))
    els.append((text_layer(s, (FB, 54), INK), 600 if name != "mid" else 680, 0.35 if name != "mid" else 1.5))
    if f: els.append((text_layer(f, (FR, 34), GREY), 700, 0.55))
    out = os.path.join(tmp, f"card_{name}.mp4")
    enc = subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                            "-s", f"{W}x{HH}", "-r", str(FPS), "-i", "-", "-f", "lavfi", "-t", str(sec), "-i",
                            "anullsrc=r=48000:cl=stereo", "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
                            "-c:a", "aac", "-shortest", out], stdin=subprocess.PIPE)
    for k in range(int(sec * FPS)):
        tt = k / FPS
        im = Image.new("RGB", (W, HH), "white")
        for layer, cy, t0 in els:
            e = ease((tt - t0) / 0.6)
            if e <= 0: continue
            a = layer.copy(); a.putalpha(a.getchannel("A").point(lambda v: int(v * e)))
            im.paste(a, ((W - a.width) // 2, int(DY + cy - a.height / 2 + 40 * (1 - e))), a)
        d = ImageDraw.Draw(im)
        if name == "mid":   # gear selector: D, then P at 0.8 s turning blue
            g = gears("D" if tt < 0.8 else "P", ease((tt - 0.8) / 0.4))
            e = ease(tt / 0.5); g.putalpha(g.getchannel("A").point(lambda v: int(v * e)))
            im.paste(g, ((W - g.width) // 2, DY + 470), g)
        else:               # blue rule under the title
            e = ease((tt - 0.4) / 0.7); half = int(90 * e)
            if half: d.rounded_rectangle([W // 2 - half, DY + 525, W // 2 + half, DY + 531], 3, fill=BLUE)
        fade = min(1.0, (sec - tt) / 0.3)
        if fade < 1: im = Image.blend(Image.new("RGB", (W, HH), "black"), im, max(fade, 0))
        enc.stdin.write(im.tobytes())
    enc.stdin.close(); enc.wait()
    return out


def strip(text, sub, path):
    im = Image.new("RGBA", (W, STRIP), (0, 0, 0, 255)); d = ImageDraw.Draw(im)
    size = 46
    while size > 30 and d.textlength(text, font=ImageFont.truetype(FB, size)) > W - 96: size -= 1
    d.text((48, 22 + (46 - size) // 2), text, font=ImageFont.truetype(FB, size), fill="white")
    if sub: d.text((48, 90), sub, font=ImageFont.truetype(FR, 30), fill=(190, 190, 190))
    im.save(path)


def scene(name, t0, t1, caps):
    v = os.path.join(RAW, f"{name}.mp4"); a = os.path.join(RAW, f"{name}.wav")
    fixed = os.path.join(RAW, f"{name}_fixed.wav")   # emulator audio dropouts patched (tools/repair_clicks.py)
    if os.path.exists(fixed): a = fixed
    off = float(open(os.path.join(RAW, f"{name}.offset")).read())
    # HOLD: freeze the picture from this time on (the voice keeps playing), e.g. before the emulator's long
    # route list appears; the emulator navigation always plans from its own default spot.
    h = HOLD.get(name, t1)
    inputs = ["-ss", str(t0), "-t", str(h - t0), "-i", v, "-ss", str(t0 + off), "-t", str(t1 - t0), "-i", a]
    fc = f"[0:v]fps=30,tpad=stop_mode=clone:stop_duration={t1 - h},scale={W}:{H},pad={W}:{H + STRIP}:0:0:black[b0]"
    last = "b0"
    # Driving view with the surrounding traffic and other lanes removed (tools/clean_driving_view.py);
    # it ends at the P shift, after which the original panel shows again.
    left = os.path.join(RAW, f"{name}_left.mp4")
    if os.path.exists(left):
        inputs += ["-ss", str(t0), "-i", left]
        fc += f";[2:v]fps=30,setpts=PTS-STARTPTS[lf];[b0][lf]overlay=0:190:eof_action=pass[bl]"
        last = "bl"
    base = 3 if os.path.exists(left) else 2
    for i, (s, e, text, sub) in enumerate(caps):
        p = os.path.join(tmp, f"{name}_{i}.png"); strip(text, sub, p); inputs += ["-i", p]
        fc += f";[{last}][{i + base}:v]overlay=0:{H}:enable='between(t,{s - t0},{e - t0})'[b{i + 1}]"; last = f"b{i + 1}"
    fc += f";[{last}]fade=in:st=0:d=0.3,format=yuv420p[v];[1:a]lowpass=f=10500,loudnorm=I=-16:TP=-1.5,aresample=48000[a]"
    out = os.path.join(tmp, f"scene_{name}.mp4")
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *inputs, "-filter_complex", fc,
         "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-crf", "20", "-preset", "medium", "-c:a", "aac", "-ar", "48000", out])
    return out


parts = [card("intro", 3.5)]
parts.append(scene(*SCENES[0][:3], SCENES[0][3]))
parts.append(card("mid", 2.5))
parts.append(scene(*SCENES[1][:3], SCENES[1][3]))
parts.append(card("end", 3.0))
lst = os.path.join(tmp, "list.txt")
open(lst, "w", encoding="utf-8").write("".join(f"file '{p}'\n" for p in parts))
joined = os.path.join(tmp, "joined.mp4")
run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst,
     "-c:v", "libx264", "-crf", "20", "-c:a", "aac", joined])
# Loudness of the whole video: the per-scene loudnorm leaves it far too quiet (~-31 LUFS, scenes are mostly
# silence), so measure once and apply one linear gain to -16 LUFS (true peak -1.5 dB).
import json
LN = "loudnorm=I=-16:TP=-1.5:LRA=20"
r = subprocess.run(["ffmpeg", "-hide_banner", "-i", joined, "-af", LN + ":print_format=json", "-f", "null", "-"],
                   capture_output=True, text=True, encoding="utf-8", errors="replace")
m = json.loads(r.stderr[r.stderr.rindex("{"):r.stderr.rindex("}") + 1])
run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", joined, "-af",
     f"{LN}:linear=true:measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}"
     f":measured_thresh={m['input_thresh']}:offset={m['target_offset']},aresample=48000",
     "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", OUT])
print("written", OUT)
