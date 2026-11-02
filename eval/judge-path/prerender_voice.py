#!/usr/bin/env python3
"""
Give the tape a voice before (or without) the Seamless recording.

A recording session without a voice server (TAPE_MODE=record, no TTS_BASE_URL) leaves every note it wanted to speak
as tts/<key>.txt on the tape — the exact spoken-form text under the exact content key the app will look up
(sha256(text|rate), see TtsClient.key). This script turns each one into tts/<key>.ogg with Meta's open-weight
MMS-TTS Telugu (facebook/mms-tts-tel), using the same model, settings and ffmpeg chain as modal/mms_tts.py, then
deletes the .txt. A later live recording with SeamlessM4T v2 overwrites the same keys.

    pip install torch --index-url https://download.pytorch.org/whl/cpu
    pip install "transformers>=4.44" scipy numpy uroman
    python3 eval/judge-path/prerender_voice.py [eval/sample-run] [--rate 0.7] [--dry-run]

Runs on a CPU in a few seconds per note. Nothing here calls a non-Meta model.
"""
import argparse
import io
import re
import subprocess
import sys
from pathlib import Path

MODEL_ID = "facebook/mms-tts-tel"


def sentences(text: str):
    parts = re.split(r"(?<=[।.!?])\s+|\n+", text)
    return [p.strip() for p in parts if p and p.strip()]


def wav_to_ogg_opus(wav: bytes) -> bytes:
    proc = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "wav", "-i", "pipe:0",
         "-af", "highpass=f=100,equalizer=f=3200:t=q:w=1.2:g=-5,lowpass=f=6500,loudnorm=I=-20:TP=-2:LRA=9",
         "-ac", "1", "-ar", "48000", "-c:a", "libopus", "-b:a", "40k", "-application", "audio",
         "-map_metadata", "-1", "-f", "ogg", "pipe:1"],
        input=wav, capture_output=True, check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError("ffmpeg failed: " + proc.stderr.decode("utf-8", "replace"))
    return proc.stdout


class Voice:
    def __init__(self, rate: float):
        import torch
        from transformers import AutoTokenizer, VitsModel
        self.torch = torch
        self.model = VitsModel.from_pretrained(MODEL_ID)
        self.tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
        self.model.eval()
        self.model.speaking_rate = float(rate)
        self.model.noise_scale = 0.55
        self.model.noise_scale_duration = 0.6
        self.sample_rate = int(self.model.config.sampling_rate)
        self.uroman = None
        if getattr(self.tokenizer, "is_uroman", False):
            import uroman as ur
            self.uroman = ur.Uroman()

    def wav(self, text: str) -> bytes:
        import numpy as np
        from scipy.io import wavfile
        chunks = []
        silence = np.zeros(int(self.sample_rate * 0.4), dtype=np.float32)
        for sentence in sentences(text) or [text]:
            prepared = re.sub(r"\s+", " ", sentence).strip()
            if self.uroman is not None:
                prepared = self.uroman.romanize_string(prepared)
            if not prepared:
                continue
            inputs = self.tokenizer(prepared, return_tensors="pt")
            if inputs["input_ids"].shape[-1] == 0:
                continue
            with self.torch.no_grad():
                wave = self.model(**inputs).waveform[0].cpu().numpy().astype(np.float32)
            chunks.append(wave)
            chunks.append(silence)
        if not chunks:
            raise ValueError("nothing to synthesize")
        audio = np.concatenate(chunks[:-1]) if len(chunks) > 1 else chunks[0]
        pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
        buf = io.BytesIO()
        wavfile.write(buf, self.sample_rate, pcm)
        return buf.getvalue()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tape", nargs="?", default="eval/sample-run")
    ap.add_argument("--rate", type=float, default=0.7, help="speaking rate the app keyed the notes with (TtsClient.DEFAULT_RATE)")
    ap.add_argument("--dry-run", action="store_true", help="list the notes, synthesize nothing")
    args = ap.parse_args()
    tts = Path(args.tape) / "tts"
    wanted = sorted(p for p in tts.glob("*.txt") if not p.with_suffix(".ogg").exists())
    stale = sorted(p for p in tts.glob("*.txt") if p.with_suffix(".ogg").exists())
    for p in stale:
        p.unlink()
    if not wanted:
        print(f"nothing to synthesize in {tts} ({len(stale)} stale .txt removed)")
        return 0
    print(f"{len(wanted)} note(s) to synthesize with {MODEL_ID} at rate {args.rate}")
    for p in wanted:
        print(f"  {p.stem[:12]}  {p.read_text(encoding='utf-8').strip()[:90]}")
    if args.dry_run:
        return 0
    voice = Voice(args.rate)
    done = 0
    for p in wanted:
        text = p.read_text(encoding="utf-8").strip()
        try:
            ogg = wav_to_ogg_opus(voice.wav(text))
        except Exception as e:  # one bad note must not stop the rest
            print(f"  skipped {p.stem[:12]}: {e}", file=sys.stderr)
            continue
        p.with_suffix(".ogg").write_bytes(ogg)
        p.unlink()
        done += 1
        print(f"  {p.stem[:12]}.ogg  {len(ogg) // 1024} KB")
    print(f"synthesized {done}/{len(wanted)}")
    return 0 if done == len(wanted) else 1


if __name__ == "__main__":
    sys.exit(main())
