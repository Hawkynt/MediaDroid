# Regenerates the Pillow-decoded `*.expected.png` references and the `hopper_*.pcx` encodes.
# Usage: python make-expected.py src/test/resources <Pillow>/Tests/images/hopper.png   (Pillow 13.0.0 was used)
import sys, os
from PIL import Image
R = sys.argv[1]; hopper = sys.argv[2]
# Pillow decodes the extra TGAs and the real PCX files: its output is the expected picture.
for d, names in (("tga", ["p_16.tga", "rgba16.tga", "rgb32rle_top_right.tga", "tga_id_field.tga", "200x32_p_bl_raw_origin.tga"]),
                 ("pcx", ["p_4_planes.pcx", "pil184.pcx", "odd_stride.pcx"])):
    for n in names:
        with Image.open(os.path.join(R, d, n)) as im:
            im.convert("RGBA").save(os.path.join(R, d, n.rsplit(".", 1)[0] + ".expected.png"))
# Pillow encodes PCX in every mode it writes, from a small odd-sized picture.
src = Image.open(hopper).convert("RGB").resize((61, 47))
for mode in ("1", "L", "P", "RGB"):
    im = src.convert(mode)
    im.save(os.path.join(R, "pcx", f"hopper_{mode.lower()}.pcx"))
    im.convert("RGBA").save(os.path.join(R, "pcx", f"hopper_{mode.lower()}.expected.png"))
