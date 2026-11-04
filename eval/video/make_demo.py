#!/usr/bin/env python3
"""
The demo video, made by machine from the judge path.

  record   — against a running Concourse in TAPE_MODE=replay: opens the simulator in its recording layout, runs the
             demo in the README's order at a pace a viewer can follow, records the browser (Playwright video), saves
             every Telugu voice note she receives with the second it appeared, writes captions, and screenshots the
             title/end cards.
  assemble — ffmpeg: title cards → the recording with her voice notes mixed in at their moments and English captions
             burned in → end cards. One mp4, 1920×1080, under three minutes.

    python3 eval/video/make_demo.py record   http://localhost:18080 judge build/video
    python3 eval/video/make_demo.py assemble build/video build/video/concourse-demo.mp4 [narration.m4a [offset-seconds]]

Everything on screen is the real app replaying a recorded run of real Meta model calls (the header says so); the
voice notes are the ones on the tape. No footage is invented.
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

W, H, FPS = 1920, 1080, 25
MAX_SECONDS = 178  # the rules stop at 3:00; keep a margin

# ---- the story, step by step --------------------------------------------------------------------------------------
# kind: api (method, path, body) | sample (name) | next (stages to fire quickly) | wait
# cap: seconds of the voice note that arrives to keep in the mix (0 = none); hold: seconds to stay on this step
# cap text in English: what the viewer reads while it happens (the meaning of what she hears, where a note plays)
STORY = [
    dict(kind="api", method="POST", path="/ops/demo-ticket", body={"child": "", "parent": "", "days_ahead": 2}, hold=9, cap=0,
         text="Her son forwards the ticket. Muse Spark reads it; the journey is built: HYD → DOH → DFW, 15 stages.",
         vo="He forwards her ticket on WhatsApp. Muse Spark reads it into an itinerary, and the journey is built: fifteen stages, Hyderabad to Dallas."),
    dict(kind="sample", name="voice-start-my-trip.wav", hold=13, cap=9,
         text="Her voice note: “start my trip.” She hears: “All right. Your trip has started. I will tell you what to do when it is time.”",
         vo="She says “start my trip”, in Telugu. Voice Transcribe and Spark understand her, and she hears — [pause: her voice note plays]"),
    dict(kind="next", stages=1, hold=12, cap=10, pip="checkin", pip_at=3.0,
         text="Check-in, at its time — her phone (right): the card and the note. “Go to the Qatar Airways counter. Give passport and ticket. Hand over the big bag. Take the boarding pass.”",
         vo="At check-in time she gets a card with the word on the board, and a voice note. [pause: her voice note plays]"),
    dict(kind="next", stages=5, gap=1.6, hold=2, cap=0,
         text="Every stage fires on the journey's clock: security, gate, boarding, the flight, Doha. Code owns the timing.",
         vo="Every stage fires on the journey's clock. Code owns the timing — the model never does."),
    dict(kind="next", stages=1, hold=6, cap=6,
         text="Doha. Her next gate is not announced yet — and that is exactly what she is told.",
         vo="Doha. Her next gate isn't announced yet, and Concourse says exactly that."),
    dict(kind="api", method="POST", path="/ops/events", body={"type": "GATE_CHANGE", "segment": 2, "new_gate": "D14", "source": "demo operations event"}, hold=12, cap=10, pip="gate", pip_at=3.0,
         text="A verified operations event sets gate D14 (simulated, labelled). Her phone: the new card and note. His phone: the update.",
         vo="A verified operations event sets gate D14 — simulated here, and labelled. Both phones get it. [pause: her voice note plays]"),
    dict(kind="sample", name="voice-where-is-my-gate.wav", hold=16, cap=12,
         text="Her voice note: “Where is my gate?” She hears: “Your Qatar Airways flight leaves from gate D14. Again: gate D14. See the card.”",
         vo="She asks where her gate is. Answered from verified facts only: [pause: her voice note plays — “your flight leaves from gate D14”]"),
    dict(kind="sample", name="sign-gates-b1-b20-left.jpg", hold=15, cap=10, pip="sign", pip_at=4.0,
         text="Her photo of a sign. SAM 3.1 finds the board, Spark reads it: gates B. She hears: “This board is not your gate. Yours is D14. Look for boards that say D.”",
         vo="She photographs a sign. SAM finds the board, Spark reads it: gates B. That's a mismatch, and she's told so. [pause: her voice note plays] No route is invented."),
    dict(kind="sample", name="voice-i-am-lost.wav", hold=12, cap=6,
         text="Her voice note: “I'm lost.” She gets her card and calm words; her son gets a message. No one claims to know where she is.",
         vo="“I'm lost.” [pause: her voice note plays] Her son is told. Concourse never claims to know where she is."),
    dict(kind="next", stages=8, gap=1.4, hold=2, cap=0,
         text="Boarding, the long flight, Dallas: arrivals, immigration (she answers the officer herself), baggage, exit.",
         vo="Boarding, the long flight, Dallas. Arrivals, immigration — she answers the officer herself — baggage, exit."),
    dict(kind="api", method="POST", path="/ops/journeys/latest/here", body=None, hold=8, cap=4, pip="here", pip_at=2.0,
         text="Outside, she presses “I'm here.” Her son's phone: journey complete.",
         vo="Outside, she presses “I'm here”. [pause: her voice note plays] Not tracked. She told her family."),
]

CARD_SECONDS = {"c1": 7, "c2": 7, "c3": 8, "c4": 9, "c5": 8}
CARD_VO = {
    "c1": "Everything you're about to see was built between October 26th and November 4th.",
    "c2": "My mother can fly nine thousand miles alone. The part she can't do is read the airport.",
    "c3": "Her son sets it up because she can't. Spark interprets her, code controls the journey, and nothing is ever invented.",
    "c4": "Every model that runs is Meta's — Spark, SAM 3.1, Voice Transcribe, and MMS for her voice — on WhatsApp, the app she already has.",
    "c5": "Thirty-six safety cases green in CI. A hundred and three model calls, replayed from a recorded run. Zero invented directions. Concourse.",
}


# ---- helpers ------------------------------------------------------------------------------------------------------

def api(base, token, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method, headers={"X-Admin-Token": token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"error": raw.decode("utf-8", "replace")[:200]}


def fetch_media(base, token, seq, out):
    req = urllib.request.Request(f"{base}/ops/outbox/{seq}/media", headers={"X-Admin-Token": token})
    with urllib.request.urlopen(req, timeout=60) as r:
        Path(out).write_bytes(r.read())


def run(cmd):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True)


def srt_time(t):
    ms = int(round(t * 1000))
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"


# ---- record ---------------------------------------------------------------------------------------------------------

def record(base, token, outdir):
    from playwright.sync_api import sync_playwright
    out = Path(outdir)
    (out / "audio").mkdir(parents=True, exist_ok=True)
    (out / "cards").mkdir(parents=True, exist_ok=True)
    status, st = api(base, token, "GET", "/ops/status")
    if status != 200:
        raise SystemExit(f"/ops/status → {status}: {st}")
    print("status:", {k: v for k, v in st.items() if k != "samples"})
    with sync_playwright() as p:
        browser = p.chromium.launch()
        # the cards first: full-screen stills for the title and the end
        cards = browser.new_page(viewport={"width": W, "height": H})
        cards.goto(f"{base}/cards.html", wait_until="domcontentloaded")
        cards.add_style_tag(content=".hint{display:none}")
        for i, cid in enumerate(CARD_SECONDS):
            cards.evaluate(f"() => show({i})")
            time.sleep(0.3)
            cards.screenshot(path=str(out / "cards" / f"{cid}.png"))
        cards.close()

        context = browser.new_context(viewport={"width": W, "height": H}, record_video_dir=str(out / "raw"),
                                      record_video_size={"width": W, "height": H})
        page = context.new_page()
        t0 = time.time()
        page.goto(f"{base}/sim.html", wait_until="domcontentloaded")
        page.fill("#token", token)
        page.dispatch_event("#token", "change")
        page.evaluate("() => { document.body.classList.add('recording'); document.body.classList.add('demo'); }")
        page.evaluate("() => { try { loadStatus(); } catch (e) {} }")
        time.sleep(2.5)

        captions, notes, pips = [], [], []
        last_seq = 0
        _, before = api(base, token, "GET", "/ops/outbox?after=0")
        if before:
            last_seq = max(e["seq"] for e in before)
        (out / "shots").mkdir(exist_ok=True)
        phone = None  # her Travel Mode screen, opened once the journey exists

        def open_phone():
            nonlocal phone
            _, entries = api(base, token, "GET", "/ops/outbox?after=0")
            link = next((line for e in reversed(entries or []) if e["kind"] == "link" for line in e["text"].splitlines() if "travel.html#" in line), None)
            if not link:
                print("  (no travel link in the outbox; no phone screen)")
                return
            phone = browser.new_page(viewport={"width": 390, "height": 844}, device_scale_factor=2)
            phone.goto(link.strip(), wait_until="domcontentloaded")
            phone.evaluate("() => { try { begin(); } catch (e) {} }")
            time.sleep(1.5)

        def shoot(name, at, seconds=7):
            if phone is None:
                return
            try:
                phone.evaluate("() => { try { refresh(true); } catch (e) {} }")
                time.sleep(1.5)
                f = out / "shots" / f"{name}.png"
                phone.screenshot(path=str(f))
                pips.append({"t": round(at, 2), "file": f.name, "seconds": seconds})
                print(f"  phone screen {name} at {at:.1f}s")
            except Exception as e:
                print(f"  (phone screen {name} failed: {e})")

        def new_voice_note(cap, at):
            nonlocal last_seq
            _, entries = api(base, token, "GET", f"/ops/outbox?after={last_seq}")
            picked = None
            for e in entries or []:
                last_seq = max(last_seq, e["seq"])
                if picked is None and e["role"] == "parent" and e["kind"] == "voice" and e.get("bytes", 0) > 0:
                    picked = e
            if picked and cap > 0:
                f = out / "audio" / f"{picked['seq']}.ogg"
                fetch_media(base, token, picked["seq"], f)
                notes.append({"t": round(at, 2), "file": f.name, "cap": cap, "seq": picked["seq"]})
                print(f"  voice note seq {picked['seq']} at {at:.1f}s (cap {cap}s)")

        planned = 0.0
        for step in STORY:
            # fixed schedule: every take has the same timeline, so a narration recorded against one cut fits the next
            wait = planned - (time.time() - t0)
            if wait > 0:
                time.sleep(wait)
            start = planned
            if step["kind"] == "api":
                code, body = api(base, token, step["method"], step["path"], step["body"])
                print(f"{start:6.1f}s {step['method']} {step['path']} → {code}")
                if code != 200:
                    raise SystemExit(f"{step['path']} failed: {body}")
                time.sleep(1.2)
                new_voice_note(step["cap"], time.time() - t0)
            elif step["kind"] == "sample":
                code, body = api(base, token, "POST", f"/ops/journeys/latest/sample/{step['name']}")
                print(f"{start:6.1f}s sample {step['name']} → {code} {json.dumps(body, ensure_ascii=False)[:120]}")
                if code != 200 or (body or {}).get("intent") == "ERROR":
                    raise SystemExit(f"sample {step['name']} did not answer from the tape: {body}")
                time.sleep(1.2)
                new_voice_note(step["cap"], time.time() - t0)
            elif step["kind"] == "next":
                for i in range(step["stages"]):
                    code, body = api(base, token, "POST", "/ops/journeys/latest/next")
                    print(f"{time.time() - t0:6.1f}s next → {code} {(body or {}).get('fired')}")
                    if code != 200:
                        raise SystemExit(f"next failed: {body}")
                    if step["stages"] > 1:
                        time.sleep(step.get("gap", 1.5))
                if step["stages"] == 1:
                    time.sleep(1.2)
                    new_voice_note(step["cap"], time.time() - t0)
            end = start + step["hold"] + (step["stages"] * step.get("gap", 1.5) if step["kind"] == "next" and step["stages"] > 1 else 0)
            captions.append({"start": round(start, 2), "end": round(end, 2), "text": step["text"]})
            if step["kind"] == "api" and step["path"] == "/ops/demo-ticket":
                open_phone()
            if step.get("pip"):
                wait = start + step["pip_at"] - (time.time() - t0)
                if wait > 0:
                    time.sleep(wait)
                shoot(step["pip"], start + step["pip_at"] + 1.5)
            if time.time() - t0 > end:
                print(f"  (step ran {time.time() - t0 - end:.1f}s over its slot)")
            planned = end
        remaining = planned + 2 - (time.time() - t0)
        if remaining > 0:
            time.sleep(remaining)
        total = planned + 2
        video = page.video
        context.close()
        raw = Path(video.path())
        browser.close()
    shutil.move(str(raw), str(out / "demo.webm"))
    # captions end where the next begins; the last one at the end of the recording
    for i, c in enumerate(captions):
        c["end"] = min(c["end"], captions[i + 1]["start"] if i + 1 < len(captions) else total)
    for c, step in zip(captions, STORY):
        c["vo"] = step["vo"]
    (out / "timeline.json").write_text(json.dumps({"total": round(total, 2), "captions": captions, "notes": notes, "pips": pips, "cards": CARD_SECONDS, "card_vo": CARD_VO}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out / "VOICEOVER.md").write_text(voiceover_sheet(captions, total), encoding="utf-8")
    print(f"recorded {total:.1f}s of demo, {len(notes)} voice notes, {len(captions)} captions → {out}")
    return 0


def mmss(t):
    return f"{int(t // 60)}:{int(t % 60):02d}"


def voiceover_sheet(captions, total):
    lead = CARD_SECONDS["c1"] + CARD_SECONDS["c2"] + CARD_SECONDS["c3"]
    lines = ["# Voice-over cue sheet", "",
             "Play `concourse-demo.mp4` with headphones, record yourself on your phone, and read each line when its time",
             "comes on screen. Where it says *[pause: her voice note plays]*, stop and let the Telugu note be heard, then go on.",
             "Natural pace; it does not have to be exact — the lines are shorter than their slots. Send the recording back as it is.", ""]
    t = 0
    for cid in ("c1", "c2", "c3"):
        lines.append(f"**{mmss(t)}** (title card) — {CARD_VO[cid]}")
        t += CARD_SECONDS[cid]
    for c in captions:
        lines.append(f"**{mmss(lead + c['start'])}** — {c['vo']}")
    t = lead + total
    for cid in ("c4", "c5"):
        lines.append(f"**{mmss(t)}** (end card) — {CARD_VO[cid]}")
        t += CARD_SECONDS[cid]
    lines += ["", f"Total: {mmss(t)}. Say the first line as soon as the first card appears; everything else follows the picture."]
    return "\n".join(lines) + "\n"


def first_speech_onset(audio, threshold_db=-35.0, min_ms=250):
    """Seconds into the file where speech starts, by ffmpeg silencedetect — to align a phone recording to the video."""
    proc = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(audio), "-af", f"silencedetect=n={threshold_db}dB:d={min_ms / 1000}", "-f", "null", "-"],
                          capture_output=True, text=True)
    starts_silent = None
    first_end = None
    for line in proc.stderr.splitlines():
        if "silence_start:" in line and starts_silent is None:
            starts_silent = float(line.split("silence_start:")[1].split()[0])
        if "silence_end:" in line:
            first_end = float(line.split("silence_end:")[1].split("|")[0])
            break
    if starts_silent is not None and starts_silent > 0.05:
        return 0.0  # it does not open with silence: speech from the start
    return first_end or 0.0


# ---- assemble ------------------------------------------------------------------------------------------------------

def still(png, seconds, out):
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-loop", "1", "-framerate", FPS, "-t", seconds, "-i", png,
         "-f", "lavfi", "-t", seconds, "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
         "-vf", f"scale={W}:{H}:force_original_aspect_ratio=decrease,pad={W}:{H}:(ow-iw)/2:(oh-ih)/2,format=yuv420p,fade=t=in:st=0:d=0.4,fade=t=out:st={seconds - 0.4}:d=0.4",
         "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-r", FPS, "-c:a", "aac", "-b:a", "128k", "-shortest", out])


def assemble(workdir, output, narration=None, narration_offset=None):
    out = Path(workdir)
    tl = json.loads((out / "timeline.json").read_text(encoding="utf-8"))
    total = tl["total"]
    lead = sum(tl["cards"][c] for c in ("c1", "c2", "c3"))
    # captions → srt
    srt = out / "demo.srt"
    with srt.open("w", encoding="utf-8") as f:
        for i, c in enumerate(tl["captions"], 1):
            f.write(f"{i}\n{srt_time(c['start'])} --> {srt_time(max(c['end'] - 0.15, c['start'] + 0.5))}\n{c['text']}\n\n")
    # the voice notes, each cut to its cap with a fade, placed at its second, mixed
    inputs = ["-i", str(out / "demo.webm")]
    filters = []
    mix = []
    for i, n in enumerate(tl["notes"], 1):
        inputs += ["-i", str(out / "audio" / n["file"])]
        cap = n["cap"]
        delay = int(n["t"] * 1000)
        filters.append(f"[{i}:a]aresample=48000,aformat=channel_layouts=stereo,atrim=0:{cap},afade=t=out:st={max(cap - 0.7, 0)}:d=0.7,adelay={delay}|{delay}[n{i}]")
        mix.append(f"[n{i}]")
    if mix:
        filters.append("".join(mix) + f"amix=inputs={len(mix)}:normalize=0:dropout_transition=0,apad[mixed]")
        audio_map = ["-map", "[mixed]"]
    else:
        filters.append(f"anullsrc=channel_layout=stereo:sample_rate=48000,atrim=0:{total}[mixed]")
        audio_map = ["-map", "[mixed]"]
    style = "FontName=DejaVu Sans,FontSize=11,PrimaryColour=&H00FFFFFF,OutlineColour=&HB0000000,BackColour=&H90000000,BorderStyle=4,Outline=1,Shadow=0,MarginV=22,MarginL=90,MarginR=90,Alignment=2"
    filters.append(f"[0:v]scale={W}:{H},format=yuv420p[base]")
    cur = "[base]"
    for k, pip in enumerate(tl.get("pips", [])):
        idx = len(inputs) // 2
        inputs += ["-i", str(out / "shots" / pip["file"])]
        a, b = pip["t"], pip["t"] + pip["seconds"]
        filters.append(f"[{idx}:v]scale=340:-1,pad=iw+12:ih+12:6:6:color=#f3c84a,fade=t=in:st={a}:d=0.3:alpha=1,fade=t=out:st={b - 0.3}:d=0.3:alpha=1[p{k}]")
        filters.append(f"{cur}[p{k}]overlay=x=1176:y=96:enable='between(t,{a},{b})'[o{k}]")
        cur = f"[o{k}]"
    filters.append(f"{cur}subtitles={srt.as_posix()}:force_style='{style}'[v]")
    demo = out / "demo.mp4"
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs, "-filter_complex", ";".join(filters),
         "-map", "[v]", *audio_map, "-t", total, "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-r", FPS,
         "-c:a", "aac", "-b:a", "128k", "-ar", "48000", demo])
    # title and end cards
    parts = []
    for cid in ("c1", "c2", "c3"):
        still(out / "cards" / f"{cid}.png", tl["cards"][cid], out / f"{cid}.mp4")
        parts.append(out / f"{cid}.mp4")
    parts.append(demo)
    for cid in ("c4", "c5"):
        still(out / "cards" / f"{cid}.png", tl["cards"][cid], out / f"{cid}.mp4")
        parts.append(out / f"{cid}.mp4")
    lst = out / "concat.txt"
    lst.write_text("".join(f"file '{p.resolve().as_posix()}'\n" for p in parts))
    silent = out / "silent.mp4" if narration else Path(output)
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", silent])
    if narration:
        # his voice over the whole film: the first word is placed 0.6 s into the first card (or at the offset given)
        onset = first_speech_onset(narration) if narration_offset is None else None
        shift = (0.6 - onset) if narration_offset is None else float(narration_offset)
        print(f"narration: first speech at {onset if onset is not None else '-'}s → shifted by {shift:+.2f}s")
        delay = f"adelay={int(shift * 1000)}|{int(shift * 1000)}" if shift >= 0 else f"atrim=start={-shift:.3f},asetpts=PTS-STARTPTS"
        run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", silent, "-i", str(narration), "-filter_complex",
             f"[1:a]aresample=48000,aformat=channel_layouts=stereo,{delay},highpass=f=80,loudnorm=I=-17:TP=-1.5:LRA=11[vo];"
             f"[0:a][vo]amix=inputs=2:normalize=0:dropout_transition=0[a]",
             "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-shortest", "-movflags", "+faststart", output])
    length = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", output]).decode().strip())
    print(f"{output}: {length:.1f}s ({int(length // 60)}:{int(length % 60):02d}), {os.path.getsize(output) // (1024 * 1024)} MB")
    if length > MAX_SECONDS:
        raise SystemExit(f"too long: {length:.1f}s > {MAX_SECONDS}s — shorten STORY holds")
    return 0


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "record" and len(sys.argv) == 5:
        sys.exit(record(sys.argv[2], sys.argv[3], sys.argv[4]))
    if len(sys.argv) >= 2 and sys.argv[1] == "assemble" and len(sys.argv) >= 4:
        narration = sys.argv[4] if len(sys.argv) >= 5 else None
        offset = sys.argv[5] if len(sys.argv) >= 6 else None
        sys.exit(assemble(sys.argv[2], sys.argv[3], narration, offset))
    print(__doc__)
    sys.exit(2)
