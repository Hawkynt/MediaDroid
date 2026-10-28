# Writes small Matroska and MP4 files carrying a container title through FFmpeg (PyAV 19.0.2) and prints
# what FFmpeg reads back - the expected values ContainerTitleTest checks.
# Usage: python make-title-fixtures.py src/test/resources/titles
import sys, av

OUT = sys.argv[1]


def video(name, fmt, title):
    c = av.open(f"{OUT}/{name}", "w", format=fmt)
    c.metadata["title"] = title
    s = c.add_stream("mpeg4", rate=10)
    s.width, s.height, s.pix_fmt = 16, 16, "yuv420p"
    for i in range(10):
        f = av.VideoFrame(16, 16, "yuv420p"); f.pts = i
        for p in s.encode(f): c.mux(p)
    for p in s.encode(): c.mux(p)
    c.close()


video("titled.mkv", "matroska", "Coco - Lebendiger als das Leben!")
video("titled.mp4", "mp4", "Das große Abenteuer")
video("untitled.mkv", "matroska", "")
for n in ["titled.mkv", "titled.mp4", "untitled.mkv"]:
    with av.open(f"{OUT}/{n}") as r:
        print(n, repr(r.metadata.get("title")))
