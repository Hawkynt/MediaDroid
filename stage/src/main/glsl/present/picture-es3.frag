#version 300 es
// A texture drawn as it is by the picture window: the stage's picture (its top in the texture's last row, uFromTop 0)
// or a subtitle of libass's (its top in the first row, uFromTop 1), its colour not premultiplied — blending does that.
precision mediump float;
in vec2 vTex;
out vec4 fragColor;
uniform sampler2D uPicture;
uniform float uFromTop;
void main() {
  fragColor = texture(uPicture, vec2(vTex.x, mix(vTex.y, 1.0 - vTex.y, uFromTop)));
}
