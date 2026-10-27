# Third-party notices — `:core:library`

## Converted code

| Source | Licence | Used for |
| --- | --- | --- |
| [FFmpeg](https://ffmpeg.org) `libavformat/mov.c` (`mov_read_chpl`, `mov_read_chap`, `mov_read_chapters`) — © the FFmpeg developers | LGPL-2.1-or-later | `Mp4Chapters` — Nero `chpl` and QuickTime chapter-track reading, converted |

Box layouts follow ISO/IEC 14496-12; Ogg pages follow RFC 3533 and comment headers the Vorbis I specification;
Ogg chapters follow the Xiph "Chapter Extension" to Vorbis comments.

## Test fixtures

`src/test/resources/chapters` was written by FFmpeg (PyAV 19.0.2, libavformat 63) with
`src/test/make-chapter-fixtures.py`; the expected chapters in `ChaptersTest` are what FFmpeg reads back from them.
