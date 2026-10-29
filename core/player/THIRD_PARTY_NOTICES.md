# Third-party notices — `:core:player`

## Converted code

| Source | Licence | Used for |
| --- | --- | --- |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_yadif.c` (`filter_line_c`, `filter_edges`, `filter_slice`) — © 2006-2011 Michael Niedermayer, 2010 James Darnley | LGPL-2.1-or-later | `Deinterlacer` YADIF, and the app's GL pass written from it |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_bwdif.c` (`filter_line_c`, `filter_edge`, `filter_slice`) — © 2016 Thomas Mundt; YADIF as above; the Weston 3-field coefficients © 2012 British Broadcasting Corporation (Jim Easterbrook, after Martin Weston, BBC R&D) | LGPL-2.1-or-later | `Deinterlacer` BWDIF, and the app's GL pass written from it |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_idet.c` (`filter`, `ff_idet_filter_line_c`) — © 2012 Michael Niedermayer | LGPL-2.1-or-later | `FieldOrder` — telling the field order from the pictures |

| [libplacebo](https://code.videolan.org/videolan/libplacebo) `src/filters.c` (`ewa_lanczossharp`, `jinc`, `pl_filter_sample`) — © Niklas Haas and the libplacebo authors; blur after Nicolas Robidoux | LGPL-2.1-or-later | `Ewa` — the EWA Lanczos-sharp weights, and the app's GL pass reading them |

The Bessel function J1 in `Ewa` follows Abramowitz & Stegun, *Handbook of Mathematical Functions*, 9.4.4 and 9.4.6.

## Test fixtures

`src/test/resources/deinterlace/` — the `input*.gray` files are synthetic, written by `make-input.py` beside
them; the `yadif-*.gray` and `bwdif-*.gray` files and the `.idet.txt` verdicts are FFmpeg 6.0's output for them,
made with the commands in that script's header.
`src/test/resources/ewa/weights.txt` — printed by `oracle.c` beside it: libplacebo's `ewa_lanczossharp` evaluated with the C
library's `j1`.
