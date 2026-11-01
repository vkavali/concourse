# Latency — to be filled from a real run (Nov 2)

Run the journey with live keys, then:

```bash
python3 eval/latency/summarize.py --base http://localhost:8080 --token "$ADMIN_TOKEN"
```

and paste the tables here. Targets: voice note in → voice note out under 10 s (Oct 28 gate); photo → verdict under 8 s;
camera frame → verdict under 4 s. The simulator's ticker shows the same numbers live.
