import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the supporter wings texture and inventory icon for
 * {@code Common/Items/Armor/Supporter_Wings.blockymodel}.
 *
 * <p>Run with: {@code java tools/WingsGen.java src/main/resources}
 *
 * <p>Four nested segments per wing, each stepping further out, back and up than its parent, so
 * the sweep accumulates down the wing.
 *
 * <p><b>ROTATION, split by axis (0.28.0).</b> 0.27.0 angled every segment about Y and mirrored
 * the sign for the opposite wing; one side swept back and the other did not, so 0.27.2 removed
 * rotation entirely and built the sweep from position. It is back now, split by axis, because
 * the two axes behave DIFFERENTLY under mirroring: reflecting through the YZ plane leaves an
 * X-axis rotation unchanged but flips the sign of a Y-axis one. So the backward lean (X) uses
 * the SAME quaternion on both wings and has no handedness to get wrong, while the outward sweep
 * (Y) is the mirrored pair. Position still carries the arc, so a wrong sign would tilt the wings
 * rather than break them.
 *
 * <p><b>Tuned three times against live looks.</b> First they read as ears: too small, anchored
 * at head height. The anchor dropped to y-8 (y-12 in 0.27.1 was then too LOW, sitting at the
 * waist), segments grew, and the texture gained real contrast — at sixteen pixels across, a
 * smooth gradient and one-pixel feather hints just look like dirt, so primaries alternate as
 * whole feathers with dark full-height quills, a hard amber leading edge and a gold tip.
 *
 * <p>The 128x128 sheet layout is authored here and mirrored exactly in the model's per-face
 * offsets. Both wings share these regions and neither mirrors them — a mirrored UV was one more
 * asymmetry to rule out while the geometry was in question:
 *
 * <pre>
 * S1 f/b 14x20 at (1,1)    l/r 3x20 at (16,1)   t/b 14x3 at (20,1)
 * S2 f/b 12x17 at (1,23)   l/r 3x17 at (14,23)  t/b 12x3 at (18,23)
 * S3 f/b  9x13 at (1,42)   l/r 3x13 at (11,42)  t/b  9x3 at (15,42)
 * S4 f/b  6x10 at (1,57)   l/r 3x10 at (8,57)   t/b  6x3 at (12,57)
 * </pre>
 */
public final class WingsGen {

    private static final int TEX = 128;
    private static final int ICON = 64;

    private static final Color FEATHER = new Color(0xF7, 0xF3, 0xE8);
    private static final Color FEATHER_MID = new Color(0xD8, 0xD1, 0xC0);
    private static final Color FEATHER_SHADE = new Color(0xA9, 0xA1, 0x8D);
    private static final Color QUILL = new Color(0x6B, 0x62, 0x50);
    private static final Color EDGE = new Color(0xE8, 0x9A, 0x2B);
    private static final Color GOLD = new Color(0xFF, 0xC9, 0x5C);
    private static final Color OUTLINE = new Color(0x4A, 0x43, 0x36);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(texture(), new File(root, "Common/Items/Armor/Supporter_Wings.png"));
        write(icon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Wings.png"));
        System.out.println("done");
    }

    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        segment(g, 1, 1, 14, 20, 4);
        edgeStrip(g, 16, 1, 3, 20);
        edgeStrip(g, 20, 1, 14, 3);

        segment(g, 1, 23, 12, 17, 3);
        edgeStrip(g, 14, 23, 3, 17);
        edgeStrip(g, 18, 23, 12, 3);

        segment(g, 1, 42, 9, 13, 3);
        edgeStrip(g, 11, 42, 3, 13);
        edgeStrip(g, 15, 42, 9, 3);

        segment(g, 1, 57, 6, 10, 2);
        edgeStrip(g, 8, 57, 3, 10);
        edgeStrip(g, 12, 57, 6, 3);
        // The outermost tip is gold: it is the part that catches the eye in silhouette.
        g.setColor(GOLD);
        g.fillRect(1, 57, 6, 3);

        g.dispose();
        return img;
    }

    /**
     * One wing face: a hard amber leading edge, then alternating light and shadowed primaries
     * separated by dark quills that run the full drop of the feather.
     */
    private static void segment(Graphics2D g, int ox, int oy, int w, int h, int feathers) {
        int step = Math.max(3, w / Math.max(1, feathers));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Alternate whole feathers light/shadowed rather than shading smoothly across
                // the panel — a gradient this small just reads as dirt.
                boolean shaded = ((x / step) % 2) == 1;
                Color base = shaded ? FEATHER_MID : FEATHER;
                // Feathers darken toward their trailing (lower) end.
                if (y > h - 4) {
                    base = shaded ? FEATHER_SHADE : FEATHER_MID;
                }
                g.setColor(base);
                g.fillRect(ox + x, oy + y, 1, 1);
            }
        }

        // Quills: full-height, dark, and they are what makes this read as plumage rather than
        // a painted slab at the size these render.
        g.setColor(QUILL);
        for (int x = step; x < w; x += step) {
            g.fillRect(ox + x, oy + 3, 1, h - 3);
        }

        // Leading edge.
        g.setColor(EDGE);
        g.fillRect(ox, oy, w, 3);
        g.setColor(GOLD);
        g.fillRect(ox, oy, w, 1);

        // Ragged trailing edge: alternate pixels dropped to a dark tone so the bottom does not
        // read as a clean cut.
        for (int x = 0; x < w; x++) {
            if ((x % 2) == 0) {
                g.setColor(QUILL);
                g.fillRect(ox + x, oy + h - 1, 1, 1);
            }
        }
        g.setColor(OUTLINE);
        g.drawRect(ox, oy, w - 1, h - 1);
    }

    /** Side and top faces: 3px edges, dark enough that the wing reads as having thickness. */
    private static void edgeStrip(Graphics2D g, int ox, int oy, int w, int h) {
        g.setColor(FEATHER_SHADE);
        g.fillRect(ox, oy, w, h);
        g.setColor(OUTLINE);
        g.drawRect(ox, oy, w - 1, h - 1);
    }

    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int side = 0; side < 2; side++) {
            int dir = side == 0 ? -1 : 1;
            int cx = (ICON / 2) + (dir * 2);
            drawSeg(g, cx, 30, 9, 20, dir);
            drawSeg(g, cx + (dir * 9), 24, 8, 17, dir);
            drawSeg(g, cx + (dir * 16), 19, 6, 13, dir);
            drawSeg(g, cx + (dir * 21), 15, 5, 9, dir);
        }
        g.dispose();
        return img;
    }

    private static void drawSeg(Graphics2D g, int x, int y, int w, int h, int dir) {
        int left = dir < 0 ? x - w : x;
        g.setColor(FEATHER);
        g.fillRect(left, y, w, h);
        g.setColor(FEATHER_MID);
        g.fillRect(left + (w / 2), y, Math.max(1, w / 3), h);
        g.setColor(GOLD);
        g.fillRect(left, y, w, 2);
        g.setColor(OUTLINE);
        g.drawRect(left, y, w - 1, h - 1);
    }

    private static void write(BufferedImage img, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("could not create " + parent);
        }
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
