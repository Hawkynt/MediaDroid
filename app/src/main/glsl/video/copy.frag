#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTex;
varying vec2 vTex;
void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
