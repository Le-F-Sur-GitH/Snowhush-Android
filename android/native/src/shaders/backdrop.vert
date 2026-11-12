#version 300 es
// What the snow falls in front of when nothing else is behind it, as in a live wallpaper: one quad over the surface.
precision highp float;

out vec2 vAt;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vAt = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
