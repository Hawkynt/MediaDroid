// A test shader for the stages' mpv shader runner (no public shader found using these): one-dimensional lookup tables
// — eight steps of 16-bit unsigned integers read with texelFetch and textureSize, a curve of 16-bit normalised values
// read linearly — and a uniform buffer (std140: a float, then a vec3 aligned to 16 bytes), over MAIN.

//!HOOK MAIN
//!BIND HOOKED
//!BIND STEPS
//!BIND CURVE
//!BIND LOOK
//!DESC one-dimensional lookup tables

vec4 hook() {
    vec4 c = HOOKED_tex(HOOKED_pos);
    int n = textureSize(STEPS, 0);
    vec3 o;
    for (int i = 0; i < 3; i++) {
        int k = clamp(int(c[i] * float(n)), 0, n - 1);
        float stepped = float(texelFetch(STEPS, k, 0).r) / 65535.0;
        o[i] = mix(stepped, texture(CURVE, c[i]).r, amount);
    }
    return vec4(o * tint, c.a);
}

//!TEXTURE STEPS
//!SIZE 8
//!FORMAT r16u
//!FILTER NEAREST
0000401f803ec05d007d409c80bbc0da

//!TEXTURE CURVE
//!SIZE 5
//!FORMAT r16
//!FILTER LINEAR
//!BORDER CLAMP
00000020007000c0ffff

//!BUFFER LOOK
//!VAR float amount
//!VAR vec3 tint
0000003f0000000000000000000000000000803f6666663fcdcc4c3f
