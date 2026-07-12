"""One-shot: record cover slide as animated WebP via Playwright video + ffmpeg."""
import http.server, os, shutil, socketserver, subprocess, tempfile, threading, time
from playwright.sync_api import sync_playwright

PORT = 9877
# Drone hover-bob frequencies in drone.js are all harmonics of 0.7 rad/s
# (sin(t*2.1), sin(t*0.7), sin(t*1.4), ...), so one full bob cycle is
# 2*pi/0.7 ≈ 8.976 s. Loop duration must be an integer multiple of that
# for a seamless wrap.
BOB_BASE_RAD_S = 0.7
LOOP_CYCLES = 1                                    # 1 cycle ≈ 8.976 s
LOOP_DURATION = LOOP_CYCLES * 2 * 3.14159265 / BOB_BASE_RAD_S
TRIM_START = 3.5                                   # skip page load + hide + settle
RECORD_SECONDS = TRIM_START + LOOP_DURATION + 0.3  # +buffer for ffmpeg
FPS = 25
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SLIDES = os.path.join(ROOT, "slides")
OUT = os.path.join(ROOT, ".github", "cover.webp")

os.chdir(SLIDES)
class Q(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a, **k): pass
httpd = socketserver.TCPServer(("127.0.0.1", PORT), Q)
threading.Thread(target=httpd.serve_forever, daemon=True).start()

tmpdir = tempfile.mkdtemp(prefix="cover_rec_")
try:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(
            viewport={"width": 1280, "height": 720},
            record_video_dir=tmpdir,
            record_video_size={"width": 1280, "height": 720},
        )
        page = context.new_page()
        page.goto(f"http://127.0.0.1:{PORT}", wait_until="networkidle")
        page.wait_for_selector(".slide.active", timeout=10000)
        page.evaluate("""() => {
            const nav = document.getElementById('nav'); if (nav) nav.style.display = 'none';
            const pr = document.getElementById('progress'); if (pr) pr.style.display = 'none';
            const s = document.querySelector('.slide.active');
            const cs = getComputedStyle(s);
            document.body.style.backgroundImage = cs.backgroundImage;
            document.body.style.backgroundColor = cs.backgroundColor;
            document.body.style.backgroundSize = 'cover';
            document.body.style.backgroundPosition = 'center';
            s.style.backgroundImage = 'none';
            s.style.backgroundColor = 'transparent';
            s.style.transform = 'scale(0.75)';
            s.style.transformOrigin = 'center center';
        }""")
        time.sleep(RECORD_SECONDS)
        video_path = page.video.path()
        page.close()
        context.close()
        browser.close()

    cmd = [
        "ffmpeg", "-y",
        "-ss", f"{TRIM_START:.4f}",
        "-i", video_path,
        "-t", f"{LOOP_DURATION:.4f}",
        "-vf", f"fps={FPS}",
        "-loop", "0",
        "-q:v", "70",
        OUT,
    ]
    subprocess.run(cmd, check=True, capture_output=True)
    size_kb = os.path.getsize(OUT) // 1024
    print(f"wrote {OUT} ({size_kb} KB, {LOOP_DURATION:.3f}s = {LOOP_CYCLES} bob cycles @ {FPS}fps)")
finally:
    httpd.shutdown()
    shutil.rmtree(tmpdir, ignore_errors=True)
