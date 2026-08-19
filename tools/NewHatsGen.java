import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Generates textures and icons for the top hat, wizard hat and beanie —
 * {@code Common/Items/Armor/Supporter_Hat_{Top,Wizard,Beanie}.blockymodel}.
 *
 * <p>Run with: {@code java tools/NewHatsGen.java src/main/resources}
 *
 * <p>Same discipline as the cowboy hat: the 96x96 sheet layout is authored HERE and mirrored
 * exactly in each model's per-face offsets. Every UV region painted below matches a
 * {@code textureLayout} offset in the corresponding blockymodel; paint outside those regions
 * decorates nothing (the cape's phantom highlight taught that).
 *
 * <pre>
 * TOP     brim t/b 46x44 (1,1)   f/b 46x2 (1,46)   l/r 44x2 (1,49)
 *         crown f/b 26x18 (55,1) l/r 24x18 (55,20) t/b 26x24 (55,39)
 * WIZARD  brim t/b 52x48 (1,1)   f/b 52x2 (1,50)   l/r 48x2 (1,53)
 *         t1 f/b 28x8 (55,1)  l/r 26x8 (55,10)  t/b 28x26 (55,19)
 *         t2 f/b 20x8 (55,46) l/r 18x8 (55,55)  t/b 20x18 (55,64)
 *         t3 f/b 12x8 (1,57)  l/r 10x8 (1,66)   t/b 12x10 (1,75)
 *         tip f/b 5x8 (20,57) l/r 5x8 (27,57)   t/b 5x5 (20,66)
 * BEANIE  shell f/b 36x12 (1,1)  l/r 35x12 (1,15)  t/b 36x35 (1,29)
 *         band  f/b 38x5 (39,1)  l/r 37x5 (39,8)   t/b 38x37 (39,14)
 *         bobble f/b 10x6 (1,66) l/r 10x6 (13,66)  t/b 10x10 (1,74)
 * </pre>
 */
public final class NewHatsGen {

    private static final int TEX = 96;
    private static final int ICON = 64;

    // Top hat: black silk, dark red band, amber pin — a formal hat that still reads as ours.
    private static final Color SILK = new Color(0x2E2E33);
    private static final Color SILK_DEEP = new Color(0x18181B);
    private static final Color SILK_SHEEN = new Color(0x45454D);
    private static final Color TOP_BAND = new Color(0x7C1F1F);
    private static final Color AMBER = new Color(0xFFAA00);

    // Wizard: deep indigo spangled with pale gold.
    private static final Color INDIGO = new Color(0x3B2A6E);
    private static final Color INDIGO_DEEP = new Color(0x211744);
    private static final Color STAR = new Color(0xEFD98A);

    // Beanie: amber knit, cream bobble — the supporter colour, worn soft.
    private static final Color KNIT = new Color(0xE89A00);
    private static final Color KNIT_DEEP = new Color(0xB47000);
    private static final Color KNIT_TRIM = new Color(0x7A4A00);
    private static final Color CREAM = new Color(0xF5EFDF);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(topHat(), new File(root, "Common/Items/Armor/Supporter_Hat_Top.png"));
        write(topIcon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Hat_Top.png"));
        write(wizard(), new File(root, "Common/Items/Armor/Supporter_Hat_Wizard.png"));
        write(wizardIcon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Hat_Wizard.png"));
        write(beanie(), new File(root, "Common/Items/Armor/Supporter_Hat_Beanie.png"));
        write(beanieIcon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Hat_Beanie.png"));
        System.out.println("done");
    }

    // --- top hat --------------------------------------------------------------------------

    private static BufferedImage topHat() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Brim top/bottom: silk darkening toward the edge, rimmed.
        for (int y = 0; y < 44; y++) {
            for (int x = 0; x < 46; x++) {
                float dx = Math.abs(x - 22.5f) / 22.5f;
                float dy = Math.abs(y - 21.5f) / 21.5f;
                g.setColor(mix(SILK, SILK_DEEP, Math.max(dx, dy) * 0.85f));
                g.fillRect(1 + x, 1 + y, 1, 1);
            }
        }
        g.setColor(SILK_DEEP);
        g.drawRect(1, 1, 45, 43);

        // Brim edges.
        g.setColor(SILK_DEEP);
        g.fillRect(1, 46, 46, 2);
        g.fillRect(1, 49, 44, 2);

        // Crown front/back: vertical sheen stripes, band on the bottom five rows, amber pin.
        // 24x26 since 0.21.3 — the first live look read as a fedora: an 18-tall crown on a
        // 46-wide brim is squat. A top hat's crown has to dominate the brim.
        crownSide(g, 55, 1, 24, 26, true);
        // Crown left/right: same, no pin.
        crownSide(g, 55, 28, 22, 26, false);

        // Crown top/bottom: flat silk with a faint ring.
        for (int y = 0; y < 22; y++) {
            for (int x = 0; x < 24; x++) {
                g.setColor(((x + y) & 3) == 0 ? SILK : mix(SILK, SILK_DEEP, 0.35f));
                g.fillRect(55 + x, 55 + y, 1, 1);
            }
        }
        g.setColor(SILK_DEEP);
        g.drawRect(55, 55, 23, 21);

        g.dispose();
        return img;
    }

    private static void crownSide(Graphics2D g, int ox, int oy, int w, int h, boolean pin) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Color c = (x % 6) == 2 ? SILK_SHEEN : mix(SILK, SILK_DEEP, y / (h * 1.9f));
                g.setColor(c);
                g.fillRect(ox + x, oy + y, 1, 1);
            }
        }
        g.setColor(TOP_BAND);
        g.fillRect(ox, oy + h - 5, w, 5);
        if (pin) {
            g.setColor(AMBER);
            g.fillRect(ox + w / 2 - 1, oy + h - 5, 3, 5);
        }
    }

    private static BufferedImage topIcon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(SILK);
        g.fillRect(18, 12, 28, 34); // crown
        g.setColor(SILK_SHEEN);
        g.fillRect(22, 12, 2, 34);
        g.setColor(TOP_BAND);
        g.fillRect(18, 40, 28, 6); // band
        g.setColor(AMBER);
        g.fillRect(30, 40, 4, 6);
        g.setColor(SILK_DEEP);
        g.fillRect(8, 46, 48, 6); // brim
        g.dispose();
        return img;
    }

    // --- wizard hat -----------------------------------------------------------------------

    private static BufferedImage wizard() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Random rnd = new Random(773); // fixed seed: the sheet must not change between runs

        // Brim top/bottom: indigo, edge-darkened, spangled.
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 52; x++) {
                float dx = Math.abs(x - 25.5f) / 25.5f;
                float dy = Math.abs(y - 23.5f) / 23.5f;
                g.setColor(mix(INDIGO, INDIGO_DEEP, Math.max(dx, dy) * 0.9f));
                g.fillRect(1 + x, 1 + y, 1, 1);
            }
        }
        g.setColor(INDIGO_DEEP);
        g.drawRect(1, 1, 51, 47);
        sprinkle(g, rnd, 1, 1, 52, 48, 10);

        // Brim edges.
        g.setColor(INDIGO_DEEP);
        g.fillRect(1, 50, 52, 2);
        g.fillRect(1, 53, 48, 2);

        // Tiers: sides get a vertical gradient and stars; tops stay plain (mostly hidden by
        // the tier above).
        tier(g, rnd, 55, 1, 28, 8, true);   // t1 f/b
        tier(g, rnd, 55, 10, 26, 8, false); // t1 l/r
        flat(g, 55, 19, 28, 26);            // t1 t/b
        tier(g, rnd, 55, 46, 20, 8, true);  // t2 f/b
        tier(g, rnd, 55, 55, 18, 8, false); // t2 l/r
        flat(g, 55, 64, 20, 18);            // t2 t/b
        tier(g, rnd, 1, 57, 12, 8, false);  // t3 f/b
        tier(g, rnd, 1, 66, 10, 8, false);  // t3 l/r
        flat(g, 1, 75, 12, 10);             // t3 t/b
        tier(g, rnd, 20, 57, 5, 8, false);  // tip f/b
        tier(g, rnd, 27, 57, 5, 8, false);  // tip l/r
        g.setColor(STAR);
        g.fillRect(20, 66, 5, 5);           // tip t/b: the very top glints gold

        g.dispose();
        return img;
    }

    private static void tier(Graphics2D g, Random rnd, int ox, int oy, int w, int h,
                             boolean starBand) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                g.setColor(mix(INDIGO, INDIGO_DEEP, (float) y / Math.max(1, h - 1) * 0.7f));
                g.fillRect(ox + x, oy + y, 1, 1);
            }
        }
        if (starBand) {
            g.setColor(STAR);
            for (int x = 1; x < w; x += 5) {
                g.fillRect(ox + x, oy + h - 2, 1, 1);
            }
        } else if (w >= 5) {
            sprinkle(g, rnd, ox, oy, w, h, Math.max(1, (w * h) / 40));
        }
    }

    private static void flat(Graphics2D g, int ox, int oy, int w, int h) {
        g.setColor(INDIGO_DEEP);
        g.fillRect(ox, oy, w, h);
    }

    private static void sprinkle(Graphics2D g, Random rnd, int ox, int oy, int w, int h, int n) {
        g.setColor(STAR);
        for (int i = 0; i < n; i++) {
            g.fillRect(ox + 2 + rnd.nextInt(Math.max(1, w - 4)),
                    oy + 2 + rnd.nextInt(Math.max(1, h - 4)), 1, 1);
        }
    }

    private static BufferedImage wizardIcon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Stepped cone silhouette.
        g.setColor(INDIGO);
        g.fillRect(28, 8, 8, 8);
        g.fillRect(24, 16, 16, 10);
        g.fillRect(19, 26, 26, 12);
        g.fillRect(14, 38, 36, 8);
        g.setColor(INDIGO_DEEP);
        g.fillRect(6, 46, 52, 6); // brim
        g.setColor(STAR);
        g.fillRect(27, 20, 2, 2);
        g.fillRect(34, 30, 2, 2);
        g.fillRect(22, 41, 2, 2);
        g.fillRect(40, 34, 2, 2);
        g.fillRect(30, 8, 4, 2); // glinting tip
        g.dispose();
        return img;
    }

    // --- beanie ---------------------------------------------------------------------------

    /**
     * The beanie sits IN the hair on purpose — v0.21.3 briefly "fixed" it to wrap over the
     * fallback haircut like the cowboy brim, and the owner's verdict was immediate: it looked
     * like a cowboy hat, and there was nothing wrong with the original. The knit band across
     * the forehead with hair above it and the bobble on top IS the beanie look on this rig.
     * Reverted the same day; do not resize it again.
     */
    private static BufferedImage beanie() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Shell: vertical knit ribs, darkening slightly toward the bottom.
        knit(g, 1, 1, 36, 12, false);  // f/b
        knit(g, 1, 15, 35, 12, false); // l/r
        // Shell top/bottom: ribs converge — painted as the same vertical ribs, slightly darker.
        for (int y = 0; y < 35; y++) {
            for (int x = 0; x < 36; x++) {
                g.setColor(mix((x % 4) < 2 ? KNIT : KNIT_DEEP, KNIT_TRIM, 0.25f));
                g.fillRect(1 + x, 29 + y, 1, 1);
            }
        }

        // Band: horizontal ribs, the rolled edge.
        knit(g, 39, 1, 38, 5, true);  // f/b
        knit(g, 39, 8, 37, 5, true);  // l/r
        for (int y = 0; y < 37; y++) {
            for (int x = 0; x < 38; x++) {
                g.setColor((y % 2) == 0 ? KNIT_DEEP : KNIT_TRIM);
                g.fillRect(39 + x, 14 + y, 1, 1);
            }
        }

        // Bobble: cream with speckle.
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 10; x++) {
                g.setColor(((x * 3 + y * 5) % 7) == 0 ? mix(CREAM, KNIT_DEEP, 0.3f) : CREAM);
                g.fillRect(1 + x, 66 + y, 1, 1);
                g.fillRect(13 + x, 66 + y, 1, 1);
            }
        }
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                g.setColor(((x * 3 + y * 5) % 7) == 0 ? mix(CREAM, KNIT_DEEP, 0.3f) : CREAM);
                g.fillRect(1 + x, 74 + y, 1, 1);
            }
        }

        g.dispose();
        return img;
    }

    private static void knit(Graphics2D g, int ox, int oy, int w, int h, boolean horizontal) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rib = horizontal ? y : x;
                g.setColor((rib % 4) < 2 ? KNIT : KNIT_DEEP);
                g.fillRect(ox + x, oy + y, 1, 1);
            }
        }
        g.setColor(KNIT_TRIM);
        g.fillRect(ox, oy + h - 1, w, 1);
    }

    private static BufferedImage beanieIcon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int x = 12; x < 52; x++) {
            g.setColor((((x - 12) % 6) < 3) ? KNIT : KNIT_DEEP);
            g.fillRect(x, 22, 1, 20);
        }
        g.setColor(KNIT_DEEP);
        g.fillRect(10, 42, 44, 8); // rolled band
        g.setColor(KNIT_TRIM);
        g.fillRect(10, 46, 44, 2);
        g.setColor(CREAM);
        g.fillRect(26, 10, 12, 12); // bobble
        g.dispose();
        return img;
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
