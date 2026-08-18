import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates textures and icons for the three accessory models: shades, chain, earrings.
 *
 * <p>Run with: {@code java tools/AccessoriesGen.java src/main/resources}
 *
 * <p>All three are static boxes on the proven Head/Chest bone anchors — no new bones, which is
 * why they shipped as one batch where the trainers (new animated bones) went alone. Each model's
 * per-face UV offsets mirror the layouts painted here.
 *
 * <p>Shades: black lenses with a glint, amber frame and temples. Chain and earrings: gold — the
 * supporter amber family — with a crown-marked medallion, matching the cape emblem, hat buckle
 * and trainer stripe.
 */
public final class AccessoriesGen {

    private static final int TEX = 64;
    private static final int ICON = 64;

    private static final Color LENS = new Color(0x16, 0x16, 0x1C);
    private static final Color LENS_GLINT = new Color(0x8A, 0x9A, 0xB8);
    private static final Color FRAME = new Color(0xFF, 0xAA, 0x00);
    private static final Color GOLD = new Color(0xFF, 0xC8, 0x3A);
    private static final Color GOLD_DEEP = new Color(0xB8, 0x86, 0x1E);
    private static final Color GOLD_DARK = new Color(0x6E, 0x4E, 0x10);
    private static final Color RUBY = new Color(0xE0, 0x3A, 0x3A);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(shadesTexture(), new File(root, "Common/Items/Armor/Supporter_Shades.png"));
        write(shadesIcon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Shades.png"));
        write(chainTexture(), new File(root, "Common/Items/Armor/Supporter_Chain.png"));
        write(chainIcon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Chain.png"));
        write(earringsTexture(), new File(root, "Common/Items/Armor/Supporter_Earrings.png"));
        write(earringsIcon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Earrings.png"));
        System.out.println("done");
    }

    // --- shades ---------------------------------------------------------------------------
    // lens front/back 32x6 @(1,1); lens sides 2x6 @(34,1); lens top/bottom 32x2 @(1,8);
    // temple sides 15x2 @(1,11); temple ends 1x2 @(17,11); temple top/bottom 1x15 @(19,11)

    private static BufferedImage shadesTexture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Lens bar: amber frame row on top, two dark lenses separated by a bridge notch, a
        // diagonal glint in each lens.
        g.setColor(LENS);
        g.fillRect(1, 1, 32, 6);
        g.setColor(FRAME);
        g.fillRect(1, 1, 32, 1);
        g.fillRect(1 + 15, 1, 2, 6);
        g.setColor(LENS_GLINT);
        g.fillRect(4, 3, 2, 1);
        g.fillRect(3, 4, 1, 1);
        g.fillRect(20, 3, 2, 1);
        g.fillRect(19, 4, 1, 1);

        // Lens sides, top and bottom: frame amber.
        g.setColor(FRAME);
        g.fillRect(34, 1, 2, 6);
        g.fillRect(1, 8, 32, 2);

        // Temples: amber all over.
        g.fillRect(1, 11, 15, 2);
        g.fillRect(17, 11, 1, 2);
        g.fillRect(19, 11, 1, 15);

        g.dispose();
        return img;
    }

    private static BufferedImage shadesIcon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // Front-on sunglasses: two rounded-ish dark lenses, amber top bar and bridge.
        g.setColor(FRAME);
        g.fillRect(6, 24, 52, 3);
        for (int side = 0; side < 2; side++) {
            int x0 = 8 + (side * 28);
            g.setColor(LENS);
            g.fillRect(x0, 27, 20, 12);
            g.fillRect(x0 + 2, 39, 16, 2);
            g.setColor(LENS_GLINT);
            g.fillRect(x0 + 3, 29, 4, 2);
            g.fillRect(x0 + 2, 31, 2, 2);
        }
        g.setColor(FRAME);
        g.fillRect(28, 27, 8, 3);

        g.dispose();
        return img;
    }

    // --- chain ----------------------------------------------------------------------------
    // strands 2x7 @(1,1), tops 2x2 @(4,1); bar front 14x2 @(1,9), ends 2x2 @(16,9),
    // top/bottom 14x2 @(1,12); medallion front 6x7 @(1,15), sides 2x7 @(8,15),
    // top/bottom 6x2 @(11,15)

    private static BufferedImage chainTexture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Strands: gold with link ticks so they read as chain, not rod.
        g.setColor(GOLD);
        g.fillRect(1, 1, 2, 7);
        g.setColor(GOLD_DEEP);
        for (int y = 2; y < 8; y += 2) {
            g.fillRect(1, y, 2, 1);
        }
        g.setColor(GOLD);
        g.fillRect(4, 1, 2, 2);

        // Bar: gold with the same link ticks.
        g.setColor(GOLD);
        g.fillRect(1, 9, 14, 2);
        g.setColor(GOLD_DEEP);
        for (int x = 2; x < 15; x += 2) {
            g.fillRect(x, 9, 1, 2);
        }
        g.setColor(GOLD);
        g.fillRect(16, 9, 2, 2);
        g.fillRect(1, 12, 14, 2);

        // Medallion: gold plate, darker rim, ruby centre.
        g.setColor(GOLD);
        g.fillRect(1, 15, 6, 7);
        g.setColor(GOLD_DEEP);
        g.drawRect(1, 15, 5, 6);
        g.setColor(RUBY);
        g.fillRect(3, 18, 2, 2);
        g.setColor(GOLD);
        g.fillRect(8, 15, 2, 7);
        g.fillRect(11, 15, 6, 2);

        g.dispose();
        return img;
    }

    private static BufferedImage chainIcon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // A necklace U with chunky links and a medallion at the bottom.
        g.setColor(GOLD);
        for (int i = 0; i < 7; i++) {
            g.fillRect(12 + (i * 2), 14 + (i * 3), 3, 3);
            g.fillRect(49 - (i * 2), 14 + (i * 3), 3, 3);
        }
        g.fillRect(26, 35, 12, 3);
        g.setColor(GOLD_DEEP);
        g.fillRect(26, 37, 12, 1);
        // Medallion.
        g.setColor(GOLD);
        g.fillRect(26, 38, 12, 14);
        g.setColor(GOLD_DEEP);
        g.drawRect(26, 38, 11, 13);
        g.setColor(RUBY);
        g.fillRect(30, 43, 4, 4);

        g.dispose();
        return img;
    }

    // --- earrings -------------------------------------------------------------------------
    // hoop faces 1..2 wide x5 @(1,1); tops 2x2 @(4,1); (7,1) top/bottom strip

    private static BufferedImage earringsTexture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Hoop faces: gold with a darker middle row, so the box reads as a ring with a gap
        // rather than a solid tag.
        g.setColor(GOLD);
        g.fillRect(1, 1, 2, 5);
        g.setColor(GOLD_DARK);
        g.fillRect(1, 3, 2, 1);
        g.setColor(GOLD);
        g.fillRect(4, 1, 2, 2);
        g.fillRect(7, 1, 2, 2);

        g.dispose();
        return img;
    }

    private static BufferedImage earringsIcon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // Two hoops side by side.
        for (int side = 0; side < 2; side++) {
            int x0 = 12 + (side * 26);
            g.setColor(GOLD);
            g.fillRect(x0, 18, 14, 4);
            g.fillRect(x0, 18, 4, 26);
            g.fillRect(x0 + 10, 18, 4, 26);
            g.fillRect(x0, 40, 14, 4);
            g.setColor(GOLD_DEEP);
            g.fillRect(x0, 43, 14, 1);
        }

        g.dispose();
        return img;
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
