// The picture's luma as ACNet reads it (mpv's LUMA plane): BT.709's weights on the stage's RGB. Adding one value to
// all three colours moves this luma by that value and leaves the colour difference alone, which is how the network's
// luma goes back into the picture (pixel-shuffle.frag).
float luma(vec3 rgb) { return dot(rgb, vec3(0.2126, 0.7152, 0.0722)); }
