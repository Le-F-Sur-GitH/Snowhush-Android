package app.snowhush.android;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Finds where the things a home screen draws begin, in a picture of the home screen's own layer: solid where it
 * drew an icon or a widget, see-through where the wallpaper shows. The snow lies on a level top with rounded
 * corners, so that is what is made of each: the top edge of what is drawn, how far it reaches to either side, and
 * how its corners curve. The wallpaper is behind the home screen, so whatever is made out too low or too round only
 * hides a little snow behind the icon, while too high or too square would leave snow hanging in the air: everything
 * here leans to the first.
 *
 * <p>Nothing here knows of Android: the picture comes in as one byte of opacity a pixel.
 */
final class Outline {
    /**
     * How opaque a pixel is, of 255, before it counts as drawn: a quarter. The shadows under icons and names and the
     * scrims along the screen's edges are fainter than this; a widget of frosted glass is not.
     */
    static final int SOLID = 64;
    /** What {@link #within} answers with: left, top, right, bottom, the corners' radius, and how square their curve is. */
    static final int LEFT = 0;
    static final int TOP = 1;
    static final int RIGHT = 2;
    static final int BOTTOM = 3;
    static final int RADIUS = 4;
    static final int EXPONENT = 5;

    /** A column whose drawing is no part of the top: see {@link #shape}. */
    private static final int APART = Integer.MAX_VALUE;
    /** How square a corner's curve can be made out to be: 2 is a circle's arc. */
    private static final float[] CURVES = {2f, 2.3f, 2.6f, 3f, 3.5f, 4f, 5f};

    private Outline() {}

    /** A picture's opacity: {@code stride} bytes a row, of which {@code width} are pixels, {@code height} rows from the top. */
    static final class Picture {
        final byte[] alpha;
        final int stride;
        final int width;
        final int height;

        Picture(byte[] alpha, int stride, int width, int height) {
            this.alpha = alpha;
            this.stride = stride;
            this.width = width;
            this.height = height;
        }

        boolean solid(int x, int y) {
            return (alpha[y * stride + x] & 0xFF) >= SOLID;
        }
    }

    /**
     * The share of the picture that is drawn on, from one pixel in sixty-four. A home screen's own layer is mostly
     * see-through; one that is solid nearly everywhere is no layer but a whole screen, or a drawer pulled over it.
     */
    static float solidShare(Picture picture) {
        int solid = 0;
        int all = 0;
        for (int y = 4; y < picture.height; y += 8) {
            for (int x = 4; x < picture.width; x += 8) {
                all++;
                if (picture.solid(x, y)) solid++;
            }
        }
        return all == 0 ? 0f : solid / (float) all;
    }

    /**
     * What is drawn inside a rectangle of the picture, as the snow can lie on it, or null when nothing there holds
     * snow: the first thing down from the top that is tall and wide enough, so an icon and not the name under it.
     * {@code dp} is how many of the picture's pixels a density-independent pixel takes.
     */
    static float[] within(Picture picture, int left, int top, int right, int bottom, float dp) {
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(picture.width, right);
        bottom = Math.min(picture.height, bottom);
        int height = bottom - top;
        if (right - left < 8 || height < 8) return null;
        int tall = Math.max(4, Math.round(12 * dp));
        int wide = Math.max(4, Math.round(16 * dp));
        // A row with less drawn in it than this is a gap: between an icon and its name there is one.
        int speck = Math.max(2, Math.round(1.5f * dp));
        int[] rows = new int[height];
        for (int y = 0; y < height; y++) {
            int count = 0;
            for (int x = left; x < right; x++) {
                if (picture.solid(x, top + y)) count++;
            }
            rows[y] = count;
        }
        int y = 0;
        while (y < height) {
            while (y < height && rows[y] < speck) y++;
            int from = y;
            int widest = 0;
            while (y < height && rows[y] >= speck) {
                widest = Math.max(widest, rows[y]);
                y++;
            }
            if (y - from >= tall && widest >= wide) {
                float[] shape = shape(picture, left, right, top + from, top + y, dp, wide);
                if (shape != null) return shape;
            }
        }
        return null;
    }

    /** The level top of what is drawn between two rows, and its corners. */
    private static float[] shape(Picture picture, int left, int right, int from, int to, float dp, int wide) {
        int width = right - left;
        // Where each column's drawing begins, or -1 for a column with none.
        int[] tops = new int[width];
        int first = -1;
        int last = -1;
        for (int x = 0; x < width; x++) {
            tops[x] = -1;
            for (int y = from; y < to; y++) {
                if (picture.solid(left + x, y)) {
                    tops[x] = y;
                    if (first < 0) first = x;
                    last = x;
                    break;
                }
            }
        }
        if (first < 0 || last - first + 1 < wide) return null;
        // The top is where most of the middle begins: a badge on a corner, sticking up, is not the top.
        int span = last - first + 1;
        int[] middle = new int[span];
        int count = 0;
        for (int x = first + span / 5; x <= last - span / 5; x++) {
            if (tops[x] >= 0) middle[count++] = tops[x];
        }
        if (count == 0) return null;
        Arrays.sort(middle, 0, count);
        int top = middle[count / 4];
        // From the middle out, for as long as the edge doesn't fall away faster than a round corner would: what is
        // lower than that is something else, a bar behind the icon or the next thing along.
        float center = (first + last) / 2f;
        int slack = Math.max(2, Math.round(2 * dp));
        int bridge = Math.round(14 * dp);
        int start = Math.round(center);
        // What sticks up above the top and has little under it is a badge hanging over the side, not more of the top.
        for (int x = first; x <= last; x++) {
            if (tops[x] < 0 || tops[x] >= top - slack) continue;
            int under = 0;
            for (int y = top; y < to; y++) {
                if (picture.solid(left + x, y)) under++;
            }
            if (under * 10 < (to - top) * 3) tops[x] = APART;
        }
        int c0 = reach(tops, start, first, -1, top, center, slack, bridge);
        int c1 = reach(tops, start + 1, last, 1, top, center, slack, bridge);
        if (c0 < 0 && c1 < 0) return null;
        if (c0 < 0) c0 = start + 1;
        if (c1 < 0) c1 = start;
        if (c1 - c0 + 1 < wide) return null;
        int drawn = 0;
        for (int x = c0; x <= c1; x++) {
            if (tops[x] >= 0) drawn++;
        }
        // Mostly gaps, as words far apart are: no ledge to lie on.
        if (drawn * 2 < c1 - c0 + 1) return null;
        // Nor is there one along a line of writing, which is more gaps than strokes: an icon or a widget is filled in.
        int filled = 0;
        for (int y = top; y < to; y++) {
            for (int x = c0; x <= c1; x++) {
                if (picture.solid(left + x, y)) filled++;
            }
        }
        if (filled * 100L < (long) (c1 - c0 + 1) * (to - top) * 42) return null;

        // How far in from each end the top is level: that is how big the corners are. A badge makes its own corner
        // look square, so the rounder of the two is believed.
        int reachLeft = level(tops, c0, c1, 1, top);
        int reachRight = level(tops, c1, c0, -1, top);
        boolean fromLeft = reachLeft >= reachRight;
        int inward = Math.max(reachLeft, reachRight);
        float half = (c1 - c0 + 1) / 2f;
        float radius = 0f;
        float exponent = 2f;
        if (inward > 1) {
            // A round corner comes level, to within the two pixels allowed for here, a little before its radius is up.
            float root = (float) (2 + Math.sqrt(4 + 4.0 * inward)) / 2f;
            radius = Math.min(root * root, half);
            float least = Float.MAX_VALUE;
            for (float curve : CURVES) {
                float error = 0f;
                int samples = 0;
                for (int quarter = 1; quarter <= 3; quarter++) {
                    int inset = Math.round(radius * quarter / 4f);
                    int x = fromLeft ? c0 + inset : c1 - inset;
                    if (x < c0 || x > c1 || tops[x] < 0) continue;
                    float fallen = Math.max(0f, Math.min(radius, tops[x] - top));
                    float u = 1f - inset / radius;
                    float curved = radius * (1f - (float) Math.pow(Math.max(0.0, 1.0 - Math.pow(u, curve)), 1.0 / curve));
                    error += (fallen - curved) * (fallen - curved);
                    samples++;
                }
                // Between two that fit alike, the rounder.
                if (samples > 0 && error < least - 0.25f) {
                    least = error;
                    exponent = curve;
                }
            }
        }
        // A pixel in from each side and down from the top: the snow's own edge is behind what is drawn.
        return new float[] {left + c0 + 1, top + 1, left + c1, to, radius, exponent};
    }

    /**
     * The furthest column from {@code from} toward {@code end} that still belongs to the top, or -1. Columns with
     * nothing drawn are crossed if there are few of them, as between the figures of a clock.
     */
    private static int reach(int[] tops, int from, int end, int step, int top, float center, int slack, int bridge) {
        int furthest = -1;
        int gap = 0;
        for (int x = from; step > 0 ? x <= end : x >= end; x += step) {
            if (x < 0 || x >= tops.length) break;
            if (tops[x] < 0) {
                if (++gap > bridge) break;
            } else if (tops[x] != APART && tops[x] - top <= Math.max(slack, Math.abs(x - center))) {
                furthest = x;
                gap = 0;
            } else {
                break;
            }
        }
        return furthest;
    }

    /** How many columns in from one end the top comes level and stays so for a moment. */
    private static int level(int[] tops, int from, int to, int step, int top) {
        int most = Math.abs(to - from) / 2;
        for (int inward = 0; inward <= most; inward++) {
            boolean flat = true;
            for (int ahead = 0; ahead < 3 && flat; ahead++) {
                int x = from + (inward + ahead) * step;
                flat = x >= 0 && x < tops.length && tops[x] >= 0 && tops[x] - top <= 1;
            }
            if (flat) return inward;
        }
        return most;
    }

    /**
     * Everything drawn that could be a widget or an icon, with nothing to go by but the picture: where each is, as
     * left, top, right and bottom, from the top of the screen down. For a launcher that tells nothing of its layout.
     */
    static ArrayList<int[]> things(Picture picture, float dp) {
        // Looked at coarsely: a cell is drawn on if its middle is.
        int cell = Math.max(2, Math.round(2 * dp));
        int columns = picture.width / cell;
        int rows = picture.height / cell;
        ArrayList<int[]> things = new ArrayList<>();
        if (columns < 4 || rows < 4) return things;
        boolean[] drawn = new boolean[columns * rows];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                drawn[row * columns + column] = picture.solid(column * cell + cell / 2, row * cell + cell / 2);
            }
        }
        int[] queue = new int[columns * rows];
        float small = 28 * dp;
        for (int seed = 0; seed < drawn.length; seed++) {
            if (!drawn[seed]) continue;
            // Everything touching this cell is one thing.
            int head = 0;
            int tail = 0;
            queue[tail++] = seed;
            drawn[seed] = false;
            int left = columns;
            int top = rows;
            int right = -1;
            int bottom = -1;
            while (head < tail) {
                int at = queue[head++];
                int column = at % columns;
                int row = at / columns;
                left = Math.min(left, column);
                right = Math.max(right, column);
                top = Math.min(top, row);
                bottom = Math.max(bottom, row);
                if (column > 0 && drawn[at - 1]) {
                    drawn[at - 1] = false;
                    queue[tail++] = at - 1;
                }
                if (column < columns - 1 && drawn[at + 1]) {
                    drawn[at + 1] = false;
                    queue[tail++] = at + 1;
                }
                if (row > 0 && drawn[at - columns]) {
                    drawn[at - columns] = false;
                    queue[tail++] = at - columns;
                }
                if (row < rows - 1 && drawn[at + columns]) {
                    drawn[at + columns] = false;
                    queue[tail++] = at + columns;
                }
            }
            int width = (right - left + 1) * cell;
            int height = (bottom - top + 1) * cell;
            // Neither a letter nor a scrim over the whole screen, and filled in as an icon or a widget is.
            boolean sized = width >= small && height >= small && width <= picture.width * 0.96f && height <= picture.height * 0.6f;
            if (sized && tail * 2 >= (right - left + 1) * (bottom - top + 1)) {
                things.add(new int[] {left * cell - cell, top * cell - cell, (right + 1) * cell + cell, (bottom + 1) * cell + cell});
            }
        }
        things.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        return things;
    }
}
