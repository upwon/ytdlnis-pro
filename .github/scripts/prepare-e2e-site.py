"""Builds the tiny local website that the emulator tests download from.

yt-dlp's generic extractor understands an HTML page with a <video> element and an English <track>, so the
"fetch subtitles online" path of the dubbing worker can be tested without depending on YouTube & co. (which block
CI IP ranges). The emulator reaches the host's loopback as 10.0.2.2.
"""
import pathlib
import shutil

assets = pathlib.Path("app/src/androidTest/assets/dubbing")
site = pathlib.Path("e2e-site")
site.mkdir(exist_ok=True)

shutil.copy(assets / "demo.mp4", site / "demo.mp4")
srt = (assets / "demo.en.srt").read_text(encoding="utf-8")
(site / "demo.en.vtt").write_text("WEBVTT\n\n" + srt.replace(",", "."), encoding="utf-8")
(site / "index.html").write_text(
    "<html><head><title>E2E demo</title></head><body>"
    '<video controls src="demo.mp4">'
    '<track kind="subtitles" srclang="en" label="English" src="demo.en.vtt" default>'
    "</video></body></html>",
    encoding="utf-8",
)
print("e2e site ready:", sorted(p.name for p in site.iterdir()))
