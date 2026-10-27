# Voice evals

`transcribe_eval.py` runs voice notes through Muse Voice Transcribe (file endpoint) and classifies intent with Muse Spark,
then writes `RESULTS.md` + `RESULTS.json` for a human to grade.

```bash
pip install requests            # ffmpeg must be on PATH
export MODEL_API_KEY=...
python3 eval/voice/transcribe_eval.py --dir ~/concourse-data/voice --out eval/voice/RESULTS.md
```

Rules: voice notes live outside the repo (`~/concourse-data/voice/`), never commit them. `RESULTS.md` may be committed
only after names/numbers are removed from the transcripts column.

Gate (Oct 27): ≥ 8/10 intents correct → GO on voice input. NO-GO → tap-button cards become the primary input, voice stays best-effort.
