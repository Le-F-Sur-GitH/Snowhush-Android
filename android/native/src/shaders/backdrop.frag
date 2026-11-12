#version 300 es
// The wallpaper's own picture, cropped to fill, or the night the icon shows: snow falling under a street lamp.
precision highp float;

uniform sampler2D picture;
// One while a picture is shown.
uniform float pictured;
// The part of the picture shown: where it starts and how far it reaches, in the picture's own coordinates.
uniform vec4 crop;
// How much of the picture's light is kept, so the snow stands out against it.
uniform float dim;
// The night's colours, at the top and at the bottom.
uniform vec3 sky;
uniform vec3 ground;
// The surface's size in points.
uniform vec2 size;
// The lamp: how far across the surface it hangs, as a share of its width, how far below the top, in points, how far
// its glow reaches, in points, and how bright it is.
uniform vec4 lamp;

in vec2 vAt;

out vec4 fragment;

const vec3 warm = vec3(1.0, 0.84, 0.59);
const vec3 iron = vec3(0.18, 0.16, 0.14);

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec3 color;
    if (pictured > 0.5) {
        // Pictures are stored top row first.
        color = texture(picture, crop.xy + vec2(vAt.x, 1.0 - vAt.y) * crop.zw).rgb * dim;
    } else {
        vec2 p = vAt * size;
        // From the bulb, in points.
        vec2 d = p - vec2(size.x * lamp.x, size.y - lamp.y);
        float edge = max(fwidth(p.y), 0.001);
        color = mix(ground, sky, smoothstep(0.0, 1.0, vAt.y));
        float halo = exp(-dot(d, d) / (lamp.z * lamp.z));
        // The beam widens as it falls, and thins out toward the ground.
        float below = max(-d.y, 0.0);
        float spread = 10.0 + below * 0.4;
        float beam = exp(-d.x * d.x / (spread * spread)) * smoothstep(0.0, 16.0, below) * exp(-below / (size.y * 0.5));
        color += warm * lamp.w * (0.22 * halo * halo + 0.07 * halo + 0.085 * beam);
        vec2 v = (vAt - vec2(0.5, 0.56)) * vec2(1.0, 0.85);
        color *= 1.0 - 0.45 * smoothstep(0.3, 0.8, length(v));
        // The lamp itself: an arm reaching in from the right, and its shade over the bulb.
        float arm = (1.0 - smoothstep(-edge, edge, abs(d.y - 17.0) - 1.1)) * step(0.0, d.x);
        float narrowing = clamp((d.y - 1.0) / 15.0, 0.0, 1.0);
        float shade = (1.0 - smoothstep(-edge, edge, abs(d.x) - mix(17.0, 8.5, narrowing))) * (1.0 - smoothstep(-edge, edge, abs(d.y - 8.5) - 7.5));
        color = mix(color, iron, max(arm, shade));
        float glow = exp(-dot(d, d) / (24.0 * 24.0));
        color += vec3(1.0, 0.93, 0.8) * lamp.w * 0.45 * glow * glow * (1.0 - shade);
        float bulb = 1.0 - smoothstep(0.75, 1.1, length(d / vec2(10.5, 2.3)));
        color = mix(color, vec3(1.0, 0.965, 0.894), bulb * lamp.w);
    }
    // A grain's worth of noise keeps the dark from banding.
    color += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
    fragment = vec4(color, 1.0);
}
