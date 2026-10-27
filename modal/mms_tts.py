"""
Concourse TTS: Meta's open-source MMS-TTS Telugu (facebook/mms-tts-tel, VITS) served on Modal.

Meta's Model API has no text-to-speech, so the Telugu voice comes from Meta's open-weight MMS model.
One container, CPU only (a sentence synthesises in ~1 s), weights cached on a Modal Volume.

Deploy:
    pip install modal && modal setup
    modal secret create concourse-tts TTS_TOKEN=<random string>      # shared secret the Java service sends
    modal deploy modal/mms_tts.py                                    # prints the URL -> TTS_BASE_URL

Try:
    curl -X POST "$TTS_BASE_URL/tts" -H "X-Concourse-Token: $TTS_TOKEN" -H "Content-Type: application/json" \
         -d '{"text": "మీ విమానం గేట్ బి పన్నెండు నుండి బయలుదేరుతుంది.", "format": "ogg", "rate": 0.9}' -o note.ogg

Request JSON: {"text": "<Telugu>", "format": "ogg" | "wav", "rate": 0.5-1.3 (1.0 = model default; lower = slower; 0.7 default)}
Response: audio/ogg (Opus, mono, 40 kbps, loudness-normalised — what WhatsApp expects for a voice note) or audio/wav.
"""

import io
import os
import re
import subprocess

import modal

MODEL_ID = "facebook/mms-tts-tel"
HF_CACHE = "/models/hf"

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("ffmpeg")
    .pip_install("torch", extra_index_url="https://download.pytorch.org/whl/cpu")
    .pip_install("transformers>=4.44", "scipy", "numpy", "uroman", "fastapi[standard]")
    .env({"HF_HOME": HF_CACHE, "TOKENIZERS_PARALLELISM": "false"})
)

app = modal.App("concourse-tts")
weights = modal.Volume.from_name("concourse-tts-models", create_if_missing=True)


@app.cls(
    image=image,
    volumes={"/models": weights},
    secrets=[modal.Secret.from_name("concourse-tts")],
    cpu=2.0,
    memory=4096,
    timeout=120,
    scaledown_window=600,
)
class TeluguTts:
    @modal.enter()
    def load(self):
        import torch
        from transformers import AutoTokenizer, VitsModel

        self.torch = torch
        self.model = VitsModel.from_pretrained(MODEL_ID)
        self.tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
        self.model.eval()
        self.sample_rate = int(self.model.config.sampling_rate)
        self.uroman = None
        if getattr(self.tokenizer, "is_uroman", False):
            # Some MMS tokenizers expect romanised input; uroman is the transliterator Meta used.
            import uroman as ur
            self.uroman = ur.Uroman()
        weights.commit()  # persist freshly downloaded weights for the next cold start

    # ---- synthesis --------------------------------------------------------------------------------------------

    def _prepare(self, text: str) -> str:
        text = re.sub(r"\s+", " ", text).strip()
        if self.uroman is not None:
            text = self.uroman.romanize_string(text)
        return text

    def _sentences(self, text: str):
        parts = re.split(r"(?<=[।.!?])\s+|\n+", text)
        return [p.strip() for p in parts if p and p.strip()]

    def synthesize_wav(self, text: str, rate: float) -> bytes:
        import numpy as np
        from scipy.io import wavfile

        torch = self.torch
        self.model.speaking_rate = float(rate)
        self.model.noise_scale = 0.55          # default 0.667: a little less breathy jitter, smoother prosody
        self.model.noise_scale_duration = 0.6  # default 0.8: steadier syllable timing
        chunks = []
        silence = np.zeros(int(self.sample_rate * 0.4), dtype=np.float32)
        for sentence in self._sentences(text) or [text]:
            prepared = self._prepare(sentence)
            if not prepared:
                continue
            inputs = self.tokenizer(prepared, return_tensors="pt")
            if inputs["input_ids"].shape[-1] == 0:
                continue
            with torch.no_grad():
                wave = self.model(**inputs).waveform[0].cpu().numpy().astype(np.float32)
            chunks.append(wave)
            chunks.append(silence)
        if not chunks:
            raise ValueError("nothing to synthesise (text has no characters the model knows)")
        audio = np.concatenate(chunks[:-1]) if len(chunks) > 1 else chunks[0]
        pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
        buf = io.BytesIO()
        wavfile.write(buf, self.sample_rate, pcm)
        return buf.getvalue()

    @staticmethod
    def wav_to_ogg_opus(wav: bytes) -> bytes:
        proc = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "wav", "-i", "pipe:0",
             # gentle clean-up for phone speakers: cut rumble, normalise loudness, then Opus at 40 kbps
             "-af", "highpass=f=100,equalizer=f=3200:t=q:w=1.2:g=-5,lowpass=f=6500,loudnorm=I=-20:TP=-2:LRA=9",
             "-ac", "1", "-ar", "48000", "-c:a", "libopus", "-b:a", "40k", "-application", "audio",
             "-map_metadata", "-1", "-f", "ogg", "pipe:1"],
            input=wav, capture_output=True, check=False,
        )
        if proc.returncode != 0:
            raise RuntimeError("ffmpeg failed: " + proc.stderr.decode("utf-8", "replace"))
        return proc.stdout

    @modal.method()
    def synthesize(self, text: str, rate: float = 0.7, fmt: str = "ogg") -> bytes:
        """Remote-callable (modal run / other Modal apps). The HTTP route below is what Concourse uses."""
        wav = self.synthesize_wav(text, rate)
        return wav if fmt == "wav" else self.wav_to_ogg_opus(wav)

    # ---- HTTP -------------------------------------------------------------------------------------------------

    @modal.asgi_app()
    def web(self):
        from fastapi import FastAPI, HTTPException, Request, Response

        api = FastAPI(title="Concourse TTS (MMS-TTS Telugu)")
        expected = os.environ.get("TTS_TOKEN", "")

        @api.get("/health")
        def health():
            return {"ok": True, "model": MODEL_ID, "sample_rate": self.sample_rate, "uroman": self.uroman is not None}

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
                rate = float(body.get("rate", 0.7))
            except (TypeError, ValueError):
                rate = 0.7
            rate = min(1.3, max(0.5, rate))
            try:
                wav = self.synthesize_wav(text, rate)
            except ValueError as e:
                raise HTTPException(status_code=422, detail=str(e))
            if fmt == "wav":
                return Response(content=wav, media_type="audio/wav")
            return Response(content=self.wav_to_ogg_opus(wav), media_type="audio/ogg")

        return api


@app.local_entrypoint()
def main(text: str = "నమస్కారం. మీ విమానం గేట్ బి పన్నెండు నుండి బయలుదేరుతుంది.", out: str = "sample.ogg"):
    """`modal run modal/mms_tts.py --text '...' --out sample.ogg` — synthesise once without deploying."""
    ogg = TeluguTts().synthesize.remote(text, 0.7, "ogg")
    with open(out, "wb") as f:
        f.write(ogg)
    print(f"wrote {out} ({len(ogg)} bytes)")
