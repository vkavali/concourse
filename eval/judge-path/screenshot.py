#!/usr/bin/env python3
"""
Screenshot the simulator after a judge-path run, for the README: both phones with the replayed voice notes and
cards, the model-call ticker, the status banner. Needs a running app (smoke.sh runs this before it stops the app
when SCREENSHOT=<path> is set) and Playwright with Chromium:

    pip install playwright && python3 -m playwright install --with-deps chromium
    python3 eval/judge-path/screenshot.py http://localhost:18080 judge docs/img/simulator.png
"""
import sys
import time


def main(base: str, token: str, out: str) -> int:
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1600, "height": 1000}, device_scale_factor=1)
        page.goto(f"{base}/sim.html", wait_until="domcontentloaded")
        page.fill("#token", token)
        page.dispatch_event("#token", "change")
        page.evaluate("() => document.body.classList.add('recording')")  # recording layout: the two phones and the ticker
        page.evaluate("() => { try { refresh(); loadStatus(); } catch (e) {} }")
        time.sleep(4)
        for feed in ("parentFeed", "childFeed", "calls"):
            page.evaluate(f"() => {{ const e = document.getElementById('{feed}'); if (e) e.scrollTop = e.scrollHeight; }}")
        time.sleep(1)
        page.screenshot(path=out, full_page=False)
        browser.close()
    print(f"screenshot → {out}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3]))
