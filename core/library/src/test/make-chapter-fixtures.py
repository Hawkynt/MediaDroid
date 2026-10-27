# Writes the chapter fixtures in src/test/resources/chapters through FFmpeg (PyAV 19.0.2, libavformat 63) and prints
# what FFmpeg itself reads back from each - the expected values ChaptersTest checks.
# Usage: python make-chapter-fixtures.py src/test/resources/chapters
import sys, fractions, av

OUT = sys.argv[1]
CHAPTERS = [("Cold Open", 0, 2000), ("Intro", 2000, 5000), ("Episode", 5000, 9000), ("End Credits", 9000, 10000)]


def chapters():
    return [{"id": i + 1, "start": s, "end": e, "time_base": fractions.Fraction(1, 1000), "metadata": {"title": t}}
            for i, (t, s, e) in enumerate(CHAPTERS)]


def video(name, fmt, opts=None):
    c = av.open(f"{OUT}/{name}", "w", format=fmt, options=opts or {})
    s = c.add_stream("mpeg4", rate=10)
    s.width, s.height, s.pix_fmt = 16, 16, "yuv420p"
    c.set_chapters(chapters())
    for i in range(100):
        f = av.VideoFrame(16, 16, "yuv420p"); f.pts = i
        for p in s.encode(f): c.mux(p)
    for p in s.encode(): c.mux(p)
    c.close()


def disable(name, box):
    """Renames one box type to 'free'-like 'xxxx' so only the other chapter form is left."""
    path = f"{OUT}/{name}"
    data = open(path, "rb").read()
    assert data.count(box) == 1, (name, box)
    open(path, "wb").write(data.replace(box, b"xxxx"))


def ogg(name):
    c = av.open(f"{OUT}/{name}", "w", format="ogg")
    s = c.add_stream("libopus", rate=48000); s.layout = "mono"
    c.set_chapters(chapters())
    pts = 0
    for i in range(500):
        f = av.AudioFrame(format="s16", layout="mono", samples=960); f.sample_rate = 48000; f.pts = pts; pts += 960
        for p in f.planes: p.update(bytes(p.buffer_size))
        for pk in s.encode(f): c.mux(pk)
    for pk in s.encode(): c.mux(pk)
    c.close()


video("both.mp4", "mp4")                       # Nero chpl and a QuickTime chapter track, moov after mdat
video("nero-only.mp4", "mp4"); disable("nero-only.mp4", b"chap")
video("quicktime-only.mov", "mov", {"movflags": "+faststart"}); disable("quicktime-only.mov", b"chpl")
ogg("chapters.ogg")

for n in ["both.mp4", "nero-only.mp4", "quicktime-only.mov", "chapters.ogg"]:
    with av.open(f"{OUT}/{n}") as r:
        print(n, [(ch["metadata"].get("title"), int(ch["start"] * ch["time_base"] * 1000)) for ch in r.chapters()])
