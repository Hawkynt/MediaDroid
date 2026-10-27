# Third-party notices — `:core:imaging`

## Converted code

| Source | Licence | Used for |
| --- | --- | --- |
| [stb_image](https://github.com/nothings/stb/blob/master/stb_image.h) by Sean Barrett | MIT or public domain (Unlicense), at the user's choice | `TgaDecoder` — converted from `stbi__tga_load` |
| [Pillow](https://github.com/python-pillow/Pillow) `PcxImagePlugin.py` and `libImaging/PcxDecode.c` — © 1995-2011 Secret Labs AB, Fredrik Lundh and contributors; © 2010 Jeffrey A. Clark and contributors | MIT-CMU (HPND) | `PcxDecoder` — converted |
| [Apache Commons Imaging](https://github.com/apache/commons-imaging) `mylzw/MyLzwDecompressor.java` and `common/PackBits.java` — © The Apache Software Foundation | Apache-2.0 | `TiffCompression` — LZW and PackBits converted |

Tag numbers, field types and the predictor follow the TIFF 6.0 specification (Adobe); the TGA colour-map origin
and right-to-left bit follow the Truevision TGA 2.0 specification.

## Test fixtures

`src/test/resources/tga` and `src/test/resources/pcx` hold files from Pillow's test suite
(`Tests/images`, MIT-CMU): the `200x32_*` TGAs and PNGs were written by GIMP, the rest are real-world files from
bug reports. The `*.expected.png` references and the `hopper_*.pcx` files were produced with Pillow 13.0.0 by
`src/test/make-expected.py` from Pillow's `hopper.png`.

### MIT-CMU licence (Pillow)

By obtaining, using, and/or copying this software and/or its associated documentation, you agree that you have
read, understood, and will comply with the following terms and conditions:

Permission to use, copy, modify and distribute this software and its documentation for any purpose and without
fee is hereby granted, provided that the above copyright notice appears in all copies, and that both that
copyright notice and this permission notice appear in supporting documentation, and that the name of Secret Labs
AB or the author not be used in advertising or publicity pertaining to distribution of the software without
specific, written prior permission.

SECRET LABS AB AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE, INCLUDING ALL IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL SECRET LABS AB OR THE AUTHOR BE LIABLE FOR ANY
SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE
USE OR PERFORMANCE OF THIS SOFTWARE.

### MIT licence (stb_image)

Copyright (c) 2017 Sean Barrett

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of
the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
IN THE SOFTWARE.

### Apache License 2.0 (Commons Imaging)

Licensed under the Apache License, Version 2.0 (the "License"); you may not use these files except in compliance
with the License. You may obtain a copy of the License at <https://www.apache.org/licenses/LICENSE-2.0>. Unless
required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

Apache Commons Imaging — Copyright The Apache Software Foundation. This product includes software developed at
The Apache Software Foundation (<https://www.apache.org/>).
