#!/usr/bin/env python3
"""
Voice gate (Day 1): run real Telugu voice notes through Muse Voice Transcribe, then classify intent with Muse Spark,
and write a results table a human fills in (correct / wrong). GO if >= 8/10 intents are correct.

Usage:
    export MODEL_API_KEY=...
    python3 eval/voice/transcribe_eval.py --dir ~/concourse-data/voice --out eval/voice/RESULTS.md

Needs: python3, `pip install requests`, ffmpeg on PATH. Voice notes stay outside the repo (never commit them).
API facts used here (verified against dev.meta.ai docs, Oct 27):
  POST https://api.meta.ai/v1/asr/transcribe?sessionId=...  multipart: request=<json>, audio=<wav mono 16-bit 16/24 kHz>
  POST https://api.meta.ai/v1/chat/completions  with response_format={"type":"json_schema","json_schema":{...}}
"""

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

try:
    import requests
except ImportError:  # pragma: no cover
    sys.exit("pip install requests")

BASE = os.environ.get("META_API_BASE_URL", "https://api.meta.ai/v1")
STT_MODEL = os.environ.get("STT_MODEL", "muse-voice-transcribe-1.0")
SPARK_MODEL = os.environ.get("SPARK_MODEL", "muse-spark-1.3")
AUDIO_EXT = {".ogg", ".opus", ".m4a", ".mp3", ".wav", ".aac", ".amr", ".mp4", ".webm"}

KEYWORDS = ["gate", "terminal", "boarding", "boarding pass", "security", "immigration", "customs", "baggage", "luggage",
            "check-in", "counter", "flight", "transit", "transfer", "wheelchair", "passport", "visa",
            "Qatar Airways", "Emirates", "Etihad", "British Airways", "Lufthansa", "American Airlines", "United",
            "Air India", "IndiGo", "Hyderabad", "Dallas", "Doha", "Dubai", "Abu Dhabi", "Frankfurt", "London",
            "DFW", "HYD", "DOH", "DXB"]

INTENT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "intent": {"type": "string", "enum": ["WHERE_IS_GATE", "AT_COUNTER", "NEED_HELP", "BAGGAGE_PROBLEM",
                                                 "CALL_SON", "START_TRIP", "I_AM_HERE", "WHAT_NEXT", "OTHER"]},
        "entity": {"type": ["string", "null"], "description": "gate code, airport, flight number or place mentioned"},
        "confidence": {"type": "number"},
        "english_gloss": {"type": "string", "description": "short English translation of what was said"},
    },
    "required": ["intent", "entity", "confidence", "english_gloss"],
}

INTENT_PROMPT = """You classify short voice-note transcripts from an elderly Telugu-speaking traveler inside an airport.
The transcript may mix Telugu and English words and may contain recognition errors. Pick the single best intent:
WHERE_IS_GATE (asks where the gate / where to go), AT_COUNTER (says she is at a counter/desk), NEED_HELP (asks for help, confused),
BAGGAGE_PROBLEM (bag missing/damaged), CALL_SON (wants her son/daughter contacted), START_TRIP (says she is starting / at the airport, ready),
I_AM_HERE (announces arrival at a place), WHAT_NEXT (asks what to do next), OTHER.
Give confidence 0-1 and a short English gloss. Never invent details that are not in the transcript."""


def wav16k(path: Path) -> bytes:
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-i", str(path),
           "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-map_metadata", "-1", "-fflags", "+bitexact", "-f", "wav", "pipe:1"]
    proc = subprocess.run(cmd, capture_output=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8", "replace"))
    return proc.stdout


def transcribe(key: str, wav: bytes, bias):
    req = {"mode": "PUSH_TO_TALK", "model": STT_MODEL, "audioEncoding": "WAV", "keywords": KEYWORDS}
    if bias:
        req["languageBias"] = bias
    r = requests.post(
        f"{BASE}/asr/transcribe", params={"sessionId": str(uuid.uuid4())},
        headers={"Authorization": f"Bearer {key}", "Accept": "application/json"},
        files={"request": (None, json.dumps(req), "application/json"), "audio": ("audio.wav", wav, "audio/wav")},
        timeout=120,
    )
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}: {r.text[:400]}")
    return r.json()


def classify(key: str, transcript: str):
    body = {
        "model": SPARK_MODEL,
        "temperature": 0.0,
        "messages": [{"role": "system", "content": INTENT_PROMPT},
                     {"role": "user", "content": f"Transcript: {transcript}"}],
        "response_format": {"type": "json_schema", "json_schema": {"name": "intent", "schema": INTENT_SCHEMA}},
    }
    r = requests.post(f"{BASE}/chat/completions", headers={"Authorization": f"Bearer {key}"}, json=body, timeout=120)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}: {r.text[:400]}")
    return json.loads(r.json()["choices"][0]["message"]["content"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="folder with voice notes (outside the repo)")
    ap.add_argument("--out", default="eval/voice/RESULTS.md")
    ap.add_argument("--bias", nargs="*", default=["Telugu", "English"], help="language bias names; pass none for auto-detect")
    ap.add_argument("--no-intent", action="store_true", help="transcribe only")
    args = ap.parse_args()

    key = os.environ.get("MODEL_API_KEY")
    if not key:
        sys.exit("MODEL_API_KEY is not set")
    files = sorted(p for p in Path(args.dir).expanduser().iterdir() if p.suffix.lower() in AUDIO_EXT)
    if not files:
        sys.exit(f"no audio files in {args.dir}")
    bias = [] if args.bias == ["none"] else args.bias

    rows = []
    for p in files:
        t0 = time.time()
        row = {"file": p.name}
        try:
            wav = wav16k(p)
            row["duration_s"] = round((len(wav) - 44) / 32000, 1)
            res = transcribe(key, wav, bias)
            row["transcript"] = (res.get("transcript") or "").strip()
            row["stt_ms"] = int((time.time() - t0) * 1000)
            if not args.no_intent and row["transcript"]:
                t1 = time.time()
                c = classify(key, row["transcript"])
                row.update({"intent": c["intent"], "entity": c["entity"], "confidence": c["confidence"],
                            "gloss": c["english_gloss"], "intent_ms": int((time.time() - t1) * 1000)})
        except Exception as e:  # keep going; the table shows what failed
            row["error"] = str(e)
        rows.append(row)
        print(f"{p.name}: {row.get('transcript', row.get('error'))!r} -> {row.get('intent', '')}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        f.write(f"# Voice gate results — {time.strftime('%Y-%m-%d %H:%M')}\n\n")
        f.write(f"Model: `{STT_MODEL}` (bias {bias or 'auto'}), intent by `{SPARK_MODEL}`. {len(rows)} notes.\n\n")
        f.write("Fill the last two columns by hand. GO if intent is correct for >= 8/10.\n\n")
        f.write("| # | file | sec | transcript | intent | entity | conf | gloss | STT ms | intent correct? (y/n) | note |\n")
        f.write("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for i, r in enumerate(rows, 1):
            cell = lambda k: str(r.get(k, "")).replace("|", "\\|").replace("\n", " ")
            f.write(f"| {i} | {cell('file')} | {cell('duration_s')} | {cell('transcript') or cell('error')} | {cell('intent')} | "
                    f"{cell('entity')} | {cell('confidence')} | {cell('gloss')} | {cell('stt_ms')} |  |  |\n")
        f.write("\n**Decision:** GO / NO-GO (fill in) — record in docs/DECISIONS.md.\n")
    with out.with_suffix(".json").open("w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print(f"\nwrote {out} and {out.with_suffix('.json')}")


if __name__ == "__main__":
    main()
