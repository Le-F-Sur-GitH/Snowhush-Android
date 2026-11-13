package app.snowhush.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * Tries {@link Outline} on home screens drawn here: icons round and squarish, with names under them and badges on
 * them, widgets solid and see-through. With a file of opacities and its width, it says what it makes out in that
 * instead, for looking at a real screen: one byte a pixel, rows from the top.
 */
public final class OutlineTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2340;
    private static final float DP = 3f;

    private static void check(boolean holds, String what) {
        if (!holds) throw new IllegalStateException(what);
    }

    private static void near(float value, float wanted, float within, String what) {
        check(Math.abs(value - wanted) <= within, what + ": " + value + ", not " + wanted);
    }

    /** Fills a superellipse standing in a rectangle: 2 for an ellipse, more for squarer. */
    private static void blob(byte[] alpha, int left, int top, int right, int bottom, double radius, double exponent, int opacity) {
        double halfWidth = (right - left) / 2.0;
        double halfHeight = (bottom - top) / 2.0;
        radius = Math.max(0.01, Math.min(radius, Math.min(halfWidth, halfHeight)));
        for (int y = Math.max(0, top); y < Math.min(HEIGHT, bottom); y++) {
            for (int x = Math.max(0, left); x < Math.min(WIDTH, right); x++) {
                double dx = Math.max(0.0, Math.abs(x + 0.5 - (left + halfWidth)) - (halfWidth - radius)) / radius;
                double dy = Math.max(0.0, Math.abs(y + 0.5 - (top + halfHeight)) - (halfHeight - radius)) / radius;
                if (Math.pow(dx, exponent) + Math.pow(dy, exponent) <= 1.0) alpha[y * WIDTH + x] = (byte) opacity;
            }
        }
    }

    /** A name under an icon: a row of small marks, with gaps between them. */
    private static void name(byte[] alpha, int left, int top, int right) {
        for (int x = left; x + 14 < right; x += 22) blob(alpha, x, top, x + 14, top + 30, 3, 2, 255);
    }

    private static String told(float[] shape) {
        if (shape == null) return "nothing";
        return String.format("[%.0f,%.0f][%.0f,%.0f] radius %.0f curve %.1f", shape[0], shape[1], shape[2], shape[3], shape[4], shape[5]);
    }

    public static void main(String[] args) throws IOException {
        if (args.length >= 2) {
            look(args[0], Integer.parseInt(args[1]), args.length > 2 ? Float.parseFloat(args[2]) : 2f);
            return;
        }
        byte[] alpha = new byte[WIDTH * HEIGHT];
        // A round icon in a cell wider and taller than it, its name under it.
        blob(alpha, 60, 400, 240, 580, 90, 2, 255);
        name(alpha, 30, 600, 270);
        // A squarish one with a badge on its top right corner, sticking up and out, and a soft shadow all round.
        blob(alpha, 322, 392, 518, 600, 60, 2.6, 40);
        blob(alpha, 330, 400, 510, 580, 54, 2.6, 255);
        blob(alpha, 480, 380, 530, 430, 25, 2, 255);
        name(alpha, 310, 600, 530);
        // A widget with round corners, and one that is only figures on nothing, as a clock is.
        blob(alpha, 48, 900, 1032, 1260, 78, 2, 255);
        for (int figure = 0; figure < 5; figure++) blob(alpha, 200 + figure * 140, 1400, 310 + figure * 140, 1560, 30, 2, 255);
        // A see-through one: a mark on the left and words on the right, at different heights.
        blob(alpha, 60, 1700, 160, 1800, 20, 2, 255);
        name(alpha, 600, 1760, 1000);
        // An icon on a bar that runs the whole width behind it, as a dock's.
        blob(alpha, 0, 2180, WIDTH, 2300, 0, 2, 255);
        blob(alpha, 450, 2080, 630, 2260, 90, 2, 255);
        Outline.Picture picture = new Outline.Picture(alpha, WIDTH, WIDTH, HEIGHT);

        float[] round = Outline.within(picture, 20, 370, 280, 640, DP);
        check(round != null, "the round icon should hold snow");
        near(round[Outline.TOP], 400, 3, "the round icon's top");
        near(round[Outline.LEFT], 60, 8, "the round icon's left");
        near(round[Outline.RIGHT], 240, 8, "the round icon's right");
        near(round[Outline.BOTTOM], 580, 2, "the round icon ends above its name");
        near(round[Outline.RADIUS], 90, 9, "the round icon's corners");
        near(round[Outline.EXPONENT], 2f, 0.31f, "the round icon's curve");

        float[] badged = Outline.within(picture, 300, 360, 540, 640, DP);
        check(badged != null, "the icon with a badge should hold snow");
        near(badged[Outline.TOP], 400, 3, "its top is the icon's, not the badge's");
        near(badged[Outline.LEFT], 330, 4, "its left");
        check(badged[Outline.RIGHT] >= 506 && badged[Outline.RIGHT] <= 532, "its right is the icon's, give or take the badge: " + badged[Outline.RIGHT]);
        near(badged[Outline.RADIUS], 54, 14, "its corners");
        check(badged[Outline.EXPONENT] >= 2f && badged[Outline.EXPONENT] <= 3.5f, "its curve: " + badged[Outline.EXPONENT]);

        float[] widget = Outline.within(picture, 30, 880, 1050, 1280, DP);
        check(widget != null, "the widget should hold snow");
        near(widget[Outline.TOP], 900, 2, "the widget's top");
        near(widget[Outline.LEFT], 48, 3, "the widget's left");
        near(widget[Outline.RIGHT], 1032, 3, "the widget's right");
        near(widget[Outline.RADIUS], 78, 10, "the widget's corners");

        float[] clock = Outline.within(picture, 150, 1380, 950, 1580, DP);
        check(clock != null, "snow lies along the top of a clock's figures");
        near(clock[Outline.TOP], 1400, 3, "the figures' top");
        check(clock[Outline.LEFT] <= 215 && clock[Outline.RIGHT] >= 855, "all the figures: " + told(clock));

        float[] scattered = Outline.within(picture, 40, 1680, 1040, 1820, DP);
        check(scattered == null || scattered[Outline.RIGHT] < 200, "nothing lies across a widget that is mostly nothing: " + told(scattered));

        float[] docked = Outline.within(picture, 420, 2060, 660, 2320, DP);
        check(docked != null, "the docked icon should hold snow");
        near(docked[Outline.TOP], 2080, 3, "the docked icon's top");
        check(docked[Outline.LEFT] >= 445 && docked[Outline.RIGHT] <= 636, "the icon and not the bar behind it: " + told(docked));

        check(Outline.within(picture, 700, 400, 900, 640, DP) == null, "where nothing is drawn nothing holds snow");
        float share = Outline.solidShare(picture);
        check(share > 0.1f && share < 0.4f, "a home screen is mostly see-through, not " + share);

        ArrayList<int[]> things = Outline.things(picture, DP);
        int held = 0;
        for (int[] thing : things) {
            if (Outline.within(picture, thing[0], thing[1], thing[2], thing[3], DP) != null) held++;
        }
        check(held >= 5 && held <= 12, "the icons, the widget, the figures and the bar are found without being told of: " + held + " of " + things.size());
        System.out.println("round " + told(round) + "; badged " + told(badged) + "; widget " + told(widget) + "; clock " + told(clock) + "; docked " + told(docked));
        System.out.println("The outlines are made out.");
    }

    /** Says what is made out in a picture kept as bytes, one thing a line: left, top, right, bottom, radius, curve. */
    private static void look(String file, int width, float dp) throws IOException {
        byte[] alpha = Files.readAllBytes(Paths.get(file));
        Outline.Picture picture = new Outline.Picture(alpha, width, width, alpha.length / width);
        System.out.println("solid " + Outline.solidShare(picture));
        for (int[] thing : Outline.things(picture, dp)) {
            float[] shape = Outline.within(picture, thing[0], thing[1], thing[2], thing[3], dp);
            System.out.println(thing[0] + " " + thing[1] + " " + thing[2] + " " + thing[3] + " -> "
                    + (shape == null ? "nothing" : shape[0] + " " + shape[1] + " " + shape[2] + " " + shape[3] + " " + shape[4] + " " + shape[5]));
        }
    }
}
