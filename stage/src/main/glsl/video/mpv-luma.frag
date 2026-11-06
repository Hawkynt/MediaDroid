// An mpv user shader's LUMA (MpvShader): the picture's Y (BT.709's weights over its R'G'B', full range, as a full-range
// BT.709 YCbCr picture's Y plane holds it) in red, for the passes hooking the brightness alone.
precision highp float;
uniform sampler2D uTex;
varying vec2 vTex;
void main() { gl_FragColor = vec4(dot(texture2D(uTex, vTex).rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 0.0, 1.0); }
