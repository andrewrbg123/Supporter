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
 * cowboy hat, trainers, shades — is axis-aligned boxes, because angling them was assumed
 * impossible. It is not: nodes carry an {@code orientation} QUATERNION and vanilla uses
 * non-identity values throughout its own models. Each wing is four nested segments, every one
 * rotated a little further about Y than its parent, so the sweep accumulates down the wing —
 * nesting composes the transforms, so no quaternion arithmetic is needed to combine them.
 *
 * <p><b>Rebuilt after the first live look</b>, which read as ears rather than wings: too small,
 * sat at head height, and the plain 2px side faces were most of what showed, so the whole thing
 * looked like flat tan flaps. Three fixes, in order of how much each mattered:
 *
 * <ol>
 *   <li><b>Bigger and lower.</b> Segments grew from 14x16 to 18x22 at the root and the anchor
 *       dropped from y+4 to y-12, so the wings sit across the back rather than beside the head.
 *   <li><b>Four segments, tapering.</b> Three read as a blob; four with a real taper reads as a
 *       wing even in silhouette, which is what most people see at distance.
 *   <li><b>Contrast.</b> The feather separations are now dark and full-height, the primaries
 *       alternate light and shadow rather than shading smoothly, and the leading edge is a hard
 *       amber band. At 18px wide, subtle is invisible.
 * </ol>
 *
 * <p>The 128x128 sheet layout is authored here and mirrored exactly in the model's per-face
 * offsets. Both wings share these regions; the right wing sets {@code mirror.x} on its front and
 * back faces so the feathers sweep the other way rather than repeating:
 *
 * <pre>
 * S1 f/b 18x22 at (1,1)    l/r 3x22 at (20,1)   t/b 18x3 at (24,1)
 * S2 f/b 14x18 at (1,25)   l/r 3x18 at (16,25)  t/b 14x3 at (20,25)
 * S3 f/b 10x14 at (1,45)   l/r 3x14 at (12,45)  t/b 10x3 at (16,45)
 * S4 f/b  7x10 at (1,61)   l/r 3x10 at (9,61)   t/b  7x3 at (13,61)
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

        segment(g, 1, 1, 18, 22, 4);
        edgeStrip(g, 20, 1, 3, 22);
        edgeStrip(g, 24, 1, 18, 3);

        segment(g, 1, 25, 14, 18, 3);
        edgeStrip(g, 16, 25, 3, 18);
        edgeStrip(g, 20, 25, 14, 3);

        segment(g, 1, 45, 10, 14, 3);
        edgeStrip(g, 12, 45, 3, 14);
        edgeStrip(g, 16, 45, 10, 3);

        segment(g, 1, 61, 7, 10, 2);
        edgeStrip(g, 9, 61, 3, 10);
        edgeStrip(g, 13, 61, 7, 3);
        // The outermost tip is gold: it is the part that catches the eye in silhouette.
        g.setColor(GOLD);
        g.fillRect(1, 61, 7, 3);

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
