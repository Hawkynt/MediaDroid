# Writes small Matroska and MP4 files carrying a container title through FFmpeg (PyAV 19.0.2) and prints
# what FFmpeg reads back - the expected values ContainerTitleTest checks: title, description, synopsis.
# Usage: python make-title-fixtures.py src/test/resources/titles
import sys, av

OUT = sys.argv[1]


def video(name, fmt, title, **tags):
    c = av.open(f"{OUT}/{name}", "w", format=fmt)
    c.metadata["title"] = title
    c.metadata.update(tags)
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
# FFmpeg's mp4 muxer writes "description" as the desc atom and "synopsis" as ldes.
video("described.mp4", "mp4", "Heat", description="Short.", synopsis="A thief and a detective, at length.")
video("short-described.mp4", "mp4", "Heat", description="Only the short one.")
for n in ["titled.mkv", "titled.mp4", "untitled.mkv", "described.mp4", "short-described.mp4"]:
    with av.open(f"{OUT}/{n}") as r:
        print(n, repr(r.metadata.get("title")), repr(r.metadata.get("description")), repr(r.metadata.get("synopsis")))
