#!/usr/bin/env python3
"""Latency table from the running app's model-call log.

    python3 eval/latency/summarize.py --base http://localhost:8080 --token $ADMIN_TOKEN

Reads /ops/models (every Meta model call the app made since start: model id, operation, ms, ok) and prints a Markdown
table of count, p50 and p95 per model and operation, plus the end-to-end parent-turn numbers from the journey's event
log (/ops/journeys/latest → voice_turn events carry per-hop ms). Paste the output into eval/latency/RESULTS.md.
Replayed calls (ms = 0, "replayed from tape") are excluded: only live calls count.
"""
import argparse
import json
import statistics
import urllib.request


def get(base, token, path):
    req = urllib.request.Request(base.rstrip("/") + path, headers={"X-Admin-Token": token})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def pct(values, p):
    if not values:
        return 0
    s = sorted(values)
    k = max(0, min(len(s) - 1, round(p / 100 * (len(s) - 1))))
    return s[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--token", required=True)
    a = ap.parse_args()

    calls = [c for c in get(a.base, a.token, "/ops/models?after=0") if c["ok"] and c["ms"] > 0 and "tape" not in (c.get("note") or "")]
    by = {}
    for c in calls:
        by.setdefault((c["model"], c["op"]), []).append(c["ms"])
    print("| Model | Operation | Calls | p50 | p95 |")
    print("|---|---|---:|---:|---:|")
    for (model, op), ms in sorted(by.items()):
        print(f"| `{model}` | {op} | {len(ms)} | {pct(ms, 50)/1000:.1f} s | {pct(ms, 95)/1000:.1f} s |")

    try:
        j = get(a.base, a.token, "/ops/journeys/latest")
    except Exception:
        return
    turns = []
    for e in j.get("events", []):
        if e.get("type") != "voice_turn" or not e.get("payload"):
            continue
        p = json.loads(e["payload"]) if isinstance(e["payload"], str) else e["payload"]
        ms = p.get("ms") or {}
        if "total" in ms:
            turns.append(ms)
    if turns:
        print()
        print("| Parent voice turn (voice note in → reply sent) | Turns | p50 | p95 |")
        print("|---|---:|---:|---:|")
        for hop in ["stt", "spark", "tts_send", "total"]:
            v = [t[hop] for t in turns if hop in t]
            print(f"| {hop} | {len(v)} | {pct(v, 50)/1000:.1f} s | {pct(v, 95)/1000:.1f} s |")
        print(f"\nmean total: {statistics.mean(t['total'] for t in turns)/1000:.1f} s over {len(turns)} turns")


if __name__ == "__main__":
    main()
