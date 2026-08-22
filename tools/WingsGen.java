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
 * <p><b>The first model here to use ROTATION.</b> Every previous self-authored piece — crown,
 * cowboy hat, trainers, shades — was axis-aligned boxes, and the wings were parked for months
 * on the assumption that angling them was not possible. It is: nodes carry an
 * {@code orientation} QUATERNION, and vanilla uses non-identity values all over its own models
 * (the Cape_Long back panel itself sits at a slight tilt). Each wing is three nested segments,
 * every one rotated a little further about Y than its parent, so the sweep accumulates down the
 * wing the way a real one does — nesting composes the transforms, so no quaternion arithmetic
 * is needed to combine them.
 *
 * <p>The 96x96 sheet layout is authored here and mirrored exactly in the model's per-face
 * offsets. Both wings share these regions; the right wing sets {@code mirror.x} on its front
 * and back faces so the feather direction reverses rather than repeating:
 *
 * <pre>
 * inner f/b 14x16 at (1,1)    l/r 2x16 at (16,1)   t/b 14x2 at (20,1)
 * mid   f/b 11x13 at (1,19)   l/r 2x13 at (13,19)  t/b 11x2 at (17,19)
 * tip   f/b  8x9  at (1,34)   l/r 2x9  at (10,34)  t/b  8x2 at (14,34)
 * </pre>
 *
 * <p>Feathered rather than membranous, and pale rather than dark: this is the most expensive
 * thing in the shop, so it reads as light — cream primaries, a warm amber leading edge, and
 * gold tips that pick up the same accent as the cape emblem and the hat buckle.
 */
public final class WingsGen {

    private static final int TEX = 96;
    private static final int ICON = 64;

    private static final Color FEATHER = new Color(0xF3, 0xEE, 0xE2);
    private static final Color FEATHER_SHADE = new Color(0xC9, 0xC2, 0xB2);
    private static final Color FEATHER_DEEP = new Color(0x9A, 0x93, 0x82);
    private static final Color EDGE = new Color(0xE8, 0x9A, 0x2B);
    private static final Color GOLD = new Color(0xFF, 0xC9, 0x5C);
    private static final Color OUTLINE = new Color(0x5A, 0x53, 0x46);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(texture(), new File(root, "Common/Items/Armor/Supporter_Wings.png"));
        write(icon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Wings.png"));
        System.out.println("done");
    }

    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Inner segment: the broadest, closest to the back. Leading edge (top) is amber, the
        // feathers run downward in rows that darken toward the trailing edge.
        segment(g, 1, 1, 14, 16, 4);
        edgeStrip(g, 16, 1, 2, 16);
        edgeStrip(g, 20, 1, 14, 2);

        // Mid segment.
        segment(g, 1, 19, 11, 13, 3);
        edgeStrip(g, 13, 19, 2, 13);
        edgeStrip(g, 17, 19, 11, 2);

        // Tip: mostly gold, because it is what catches the eye from a distance.
        segment(g, 1, 34, 8, 9, 2);
        edgeStrip(g, 10, 34, 2, 9);
        edgeStrip(g, 14, 34, 8, 2);
        g.setColor(GOLD);
        g.fillRect(1, 34, 8, 2);

        g.dispose();
        return img;
    }

    /**
     * One wing face: an amber leading edge along the top, then rows of feathers stepping
     * outward, each row a shade deeper so the wing reads as layered rather than flat.
     */
    private static void segment(Graphics2D g, int ox, int oy, int w, int h, int feathers) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float depth = (float) y / Math.max(1, h - 1);
                Color base = depth < 0.55f ? FEATHER : mix(FEATHER, FEATHER_SHADE, depth);
                g.setColor(base);
                g.fillRect(ox + x, oy + y, 1, 1);
            }
        }
        // Leading edge.
        g.setColor(EDGE);
        g.fillRect(ox, oy, w, 2);
        g.setColor(GOLD);
        g.fillRect(ox, oy, w, 1);

        // Feather separations: vertical breaks that stop short of the leading edge, so the
        // quills read as attached rather than as stripes across the whole panel.
        int step = Math.max(2, w / Math.max(1, feathers));
        g.setColor(FEATHER_DEEP);
        for (int x = step; x < w; x += step) {
            g.fillRect(ox + x, oy + 3, 1, h - 4);
        }
        // Trailing edge scallop: the bottom row alternates, which at this scale reads as the
        // ragged end of feathers rather than a cut line.
        for (int x = 0; x < w; x++) {
            if ((x / Math.max(1, step / 2)) % 2 == 0) {
                g.setColor(FEATHER_DEEP);
                g.fillRect(ox + x, oy + h - 1, 1, 1);
            }
        }
        g.setColor(OUTLINE);
        g.drawRect(ox, oy, w - 1, h - 1);
    }

    /** The thin side and top faces: 2px edges, kept dark so the wing has visible thickness. */
    private static void edgeStrip(Graphics2D g, int ox, int oy, int w, int h) {
        g.setColor(FEATHER_SHADE);
        g.fillRect(ox, oy, w, h);
        g.setColor(OUTLINE);
        g.drawRect(ox, oy, w - 1, h - 1);
    }

    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Two wings swept up and out from a central gap, drawn as stepped blocks so the icon
        // matches the model's segmented silhouette.
        for (int side = 0; side < 2; side++) {
            int dir = side == 0 ? -1 : 1;
            int cx = ICON / 2 + (dir * 3);
            drawSeg(g, cx + (dir * 2), 26, 10, 18, dir);
            drawSeg(g, cx + (dir * 11), 20, 8, 15, dir);
            drawSeg(g, cx + (dir * 18), 15, 6, 11, dir);
        }
        g.dispose();
        return img;
    }

    private static void drawSeg(Graphics2D g, int x, int y, int w, int h, int dir) {
        int left = dir < 0 ? x - w : x;
        g.setColor(FEATHER);
        g.fillRect(left, y, w, h);
        g.setColor(GOLD);
        g.fillRect(left, y, w, 2);
        g.setColor(OUTLINE);
        g.drawRect(left, y, w - 1, h - 1);
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + ((b.getRed() - a.getRed()) * t)),
                Math.round(a.getGreen() + ((b.getGreen() - a.getGreen()) * t)),
                Math.round(a.getBlue() + ((b.getBlue() - a.getBlue()) * t)));
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
