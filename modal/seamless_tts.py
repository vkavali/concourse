"""
Concourse TTS, option B: Meta's SeamlessM4T v2 Large (open weights) speaking Telugu, served on Modal with a GPU.

SeamlessM4T v2 is a translation model whose text-to-speech-translation task (T2ST) produces Telugu speech from Telugu
text through a unit vocoder with 200 selectable speakers — usually smoother than the single MMS-TTS voice. Same HTTP
contract as modal/mms_tts.py, so the Java service only needs TTS_BASE_URL pointed here.

Deploy:
    modal deploy modal/seamless_tts.py            # prints the URL -> TTS_BASE_URL (uses the same concourse-tts secret)

Try (pick a speaker you like; 0-199):
    curl -X POST "$TTS_BASE_URL/tts" -H "X-Concourse-Token: $TTS_TOKEN" -H "Content-Type: application/json" \
         -d '{"text": "నమస్కారం. మీ విమానం గేట్ బి పన్నెండు నుండి బయలుదేరుతుంది.", "format": "ogg", "speaker": 7}' -o note.ogg

Caveat: T2ST re-generates the sentence through the text decoder; it may occasionally reword. Concourse's safety check runs
on the text, so prefer short, plain sentences and keep flight/gate codes spelled out as words (TeluguSpeech does this).
"""

import io
import os
import re
import subprocess

import modal

MODEL_ID = "facebook/seamless-m4t-v2-large"
HF_CACHE = "/models/hf"

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("ffmpeg")
    .pip_install("torch", "torchaudio", index_url="https://download.pytorch.org/whl/cu124")
    .pip_install("transformers>=4.44", "sentencepiece", "scipy", "numpy", "fastapi[standard]", "accelerate")
    .env({"HF_HOME": HF_CACHE, "TOKENIZERS_PARALLELISM": "false"})
)

app = modal.App("concourse-tts-seamless")
weights = modal.Volume.from_name("concourse-tts-models", create_if_missing=True)


@app.cls(
    image=image,
    gpu="L4",
    volumes={"/models": weights},
    secrets=[modal.Secret.from_name("concourse-tts")],
    memory=16384,
    timeout=180,
    scaledown_window=900,
)
class SeamlessTeluguTts:
    @modal.enter()
    def load(self):
        import torch
        from transformers import AutoProcessor, SeamlessM4Tv2ForTextToSpeech

        self.torch = torch
        self.processor = AutoProcessor.from_pretrained(MODEL_ID)
        self.model = SeamlessM4Tv2ForTextToSpeech.from_pretrained(MODEL_ID, torch_dtype=torch.float16).to("cuda").eval()
        self.sample_rate = int(self.model.config.sampling_rate)
        weights.commit()

    def _sentences(self, text: str):
        parts = re.split(r"(?<=[।.!?])\s+|\n+", re.sub(r"\s+", " ", text).strip())
        return [p.strip() for p in parts if p and p.strip()]

    def synthesize_wav(self, text: str, speaker: int) -> bytes:
        import numpy as np
        from scipy.io import wavfile

        torch = self.torch
        chunks = []
        silence = np.zeros(int(self.sample_rate * 0.4), dtype=np.float32)
        for sentence in self._sentences(text) or [text]:
            inputs = self.processor(text=sentence, src_lang="tel", return_tensors="pt").to("cuda")
            with torch.no_grad():
                out = self.model.generate(**inputs, tgt_lang="tel", speaker_id=int(speaker))
            wave = out[0].float().cpu().numpy().squeeze().astype(np.float32)
            chunks.append(wave)
            chunks.append(silence)
        if not chunks:
            raise ValueError("nothing to synthesise")
        audio = np.concatenate(chunks[:-1]) if len(chunks) > 1 else chunks[0]
        peak = float(np.max(np.abs(audio))) or 1.0
        pcm = (np.clip(audio / peak * 0.9, -1.0, 1.0) * 32767).astype(np.int16)
        buf = io.BytesIO()
        wavfile.write(buf, self.sample_rate, pcm)
        return buf.getvalue()

    @staticmethod
    def wav_to_ogg_opus(wav: bytes) -> bytes:
        proc = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "wav", "-i", "pipe:0",
             "-af", "highpass=f=100,loudnorm=I=-20:TP=-2:LRA=9",
             "-ac", "1", "-ar", "48000", "-c:a", "libopus", "-b:a", "40k", "-application", "audio",
             "-map_metadata", "-1", "-f", "ogg", "pipe:1"],
            input=wav, capture_output=True, check=False,
        )
        if proc.returncode != 0:
            raise RuntimeError("ffmpeg failed: " + proc.stderr.decode("utf-8", "replace"))
        return proc.stdout

    @modal.method()
    def synthesize(self, text: str, speaker: int = 0, fmt: str = "ogg") -> bytes:
        wav = self.synthesize_wav(text, speaker)
        return wav if fmt == "wav" else self.wav_to_ogg_opus(wav)

    @modal.asgi_app()
    def web(self):
        from fastapi import FastAPI, HTTPException, Request, Response

        api = FastAPI(title="Concourse TTS (SeamlessM4T v2 Telugu)")
        expected = os.environ.get("TTS_TOKEN", "")

        @api.get("/health")
        def health():
            return {"ok": True, "model": MODEL_ID, "sample_rate": self.sample_rate, "speakers": 200}

        @api.post("/tts")
        async def tts(request: Request):
            if expected and request.headers.get("x-concourse-token", "") != expected:
                raise HTTPException(status_code=401, detail="bad token")
            body = await request.json()
            text = (body or {}).get("text", "")
            if not isinstance(text, str) or not text.strip():
                raise HTTPException(status_code=400, detail="text is required")
            if len(text) > 1500:
                raise HTTPException(status_code=413, detail="text too long (max 1500 chars)")
            fmt = (body.get("format") or "ogg").lower()
            try:
                speaker = int(body.get("speaker", 0))
            except (TypeError, ValueError):
                speaker = 0
            speaker = max(0, min(199, speaker))
            try:
                wav = self.synthesize_wav(text, speaker)
            except ValueError as e:
                raise HTTPException(status_code=422, detail=str(e))
            if fmt == "wav":
                return Response(content=wav, media_type="audio/wav")
            return Response(content=self.wav_to_ogg_opus(wav), media_type="audio/ogg")

        return api


@app.local_entrypoint()
def main(text: str = "నమస్కారం. మీ విమానం గేట్ బి పన్నెండు నుండి బయలుదేరుతుంది.", speaker: int = 0, out: str = "seamless.ogg"):
    """`modal run modal/seamless_tts.py --speaker 7 --out s7.ogg` — synthesise once without deploying."""
    ogg = SeamlessTeluguTts().synthesize.remote(text, speaker, "ogg")
    with open(out, "wb") as f:
        f.write(ogg)
    print(f"wrote {out} ({len(ogg)} bytes)")
