import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the Supporter Crown texture and inventory icon.
 *
 * <p>Run with: {@code java tools/CrownTextureGen.java src/main/resources}
 *
 * <p>The crown is the plugin's first SELF-AUTHORED geometry —
 * {@code Common/Items/Armor/Supporter_Crown.blockymodel}, hand-written against the box schema
 * read out of Violet's Wardrobe's beanie (root node named "Head" as the bone anchor, box
 * children, position = bottom-centre, per-face UV offsets). Unlike the cape, every UV region
 * here is ours, so the sheet layout below is authoritative rather than reverse-engineered:
 *
 * <pre>
 * band front/back   34x6  at (1,1)
 * band left/right   33x6  at (1,9)
 * band top/bottom   34x33 at (1,17)
 * spike sides        4x6  at (40,1)
 * spike top/bottom   4x4  at (40,9)
 * jewel front/back   6x4  at (48,1)
 * jewel left/right   2x4  at (56,1)
 * jewel top/bottom   6x2  at (48,7)
 * </pre>
 *
 * <p>Amber only for now, matching {@code tagColorHex} — one design until the bone attachment
 * and scale are proven in game, exactly as the cape went one design first.
 */
public final class CrownTextureGen {

    private static final int TEX = 64;
    private static final int ICON = 64;

    private static final Color AMBER = new Color(0xFFAA00);
    private static final Color AMBER_DEEP = new Color(0xC97C2A);
    private static final Color TRIM = new Color(0x4A, 0x33, 0x12);
    private static final Color HIGHLIGHT = new Color(0xFF, 0xD1, 0x6B);
    /** Jewel in ember red — contrast against the gold, and it reads as a gem, not a stud. */
    private static final Color JEWEL = new Color(0xE0, 0x3A, 0x3A);
    private static final Color JEWEL_DEEP = new Color(0x8C, 0x1F, 0x1F);

    private static final String[] CROWN_ICON = {
        "X.....X.....X",
        "X.....X.....X",
        "XX...XXX...XX",
        "XXX.XXXXX.XXX",
        "XXXXXXXXXXXXX",
        "XXXXXXXXXXXXX",
        ".XXXXXXXXXXX.",
        ".XXXXXXXXXXX.",
        ".XXXXXXXXXXX.",
    };

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(texture(), new File(root, "Common/Items/Armor/Supporter_Crown.png"));
        write(icon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Crown.png"));
        System.out.println("done");
    }

    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Band front/back and left/right: vertical gold gradient with a trim row top and
        // bottom, and gem dots spaced along the front.
        bandStrip(g, 1, 1, 34, 6, true);
        bandStrip(g, 1, 9, 33, 6, false);

        // Band top/bottom: the ring seen from above; deep gold, edged.
        g.setColor(AMBER_DEEP);
        g.fillRect(1, 17, 34, 33);
        g.setColor(TRIM);
        g.drawRect(1, 17, 33, 32);

        // Spike sides: gradient bright at the top so the tips catch light.
        for (int y = 0; y < 6; y++) {
            g.setColor(mix(HIGHLIGHT, AMBER, y / 5f));
            g.fillRect(40, 1 + y, 4, 1);
        }
        // Spike top.
        g.setColor(HIGHLIGHT);
        g.fillRect(40, 9, 4, 4);

        // Jewel front/back, sides, top: red gem with a bright glint pixel.
        for (int y = 0; y < 4; y++) {
            g.setColor(mix(JEWEL, JEWEL_DEEP, y / 3f));
            g.fillRect(48, 1 + y, 6, 1);
        }
        g.setColor(Color.WHITE);
        g.fillRect(49, 2, 1, 1);
        g.setColor(JEWEL_DEEP);
        g.fillRect(56, 1, 2, 4);
        g.fillRect(48, 7, 6, 2);

        g.dispose();
        return img;
    }

    /** A horizontal band segment: trim edges, gold body, and gem dots if it is the front. */
    private static void bandStrip(Graphics2D g, int x, int y, int w, int h, boolean gems) {
        for (int row = 0; row < h; row++) {
            g.setColor(mix(AMBER, AMBER_DEEP, row / (float) (h - 1)));
            g.fillRect(x, y + row, w, 1);
        }
        g.setColor(TRIM);
        g.fillRect(x, y, w, 1);
        g.fillRect(x, y + h - 1, w, 1);
        if (gems) {
            g.setColor(HIGHLIGHT);
            for (int gx = x + 4; gx < x + w - 3; gx += 8) {
                g.fillRect(gx, y + 2, 2, 2);
            }
        }
    }

    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // The same crown mark the capes carry, at scale 3 so it fills the icon — the item IS
        // the crown, where on the cape it was an emblem.
        int scale = 3;
        int x0 = (ICON - CROWN_ICON[0].length() * scale) / 2;
        int y0 = (ICON - CROWN_ICON.length * scale) / 2;
        g.setColor(TRIM);
        for (int r = 0; r < CROWN_ICON.length; r++) {
            for (int c = 0; c < CROWN_ICON[r].length(); c++) {
                if (CROWN_ICON[r].charAt(c) == 'X') {
                    g.fillRect(x0 + c * scale - 1, y0 + r * scale - 1, scale + 2, scale + 2);
                }
            }
        }
        for (int r = 0; r < CROWN_ICON.length; r++) {
            for (int c = 0; c < CROWN_ICON[r].length(); c++) {
                if (CROWN_ICON[r].charAt(c) == 'X') {
                    g.setColor(mix(AMBER, AMBER_DEEP, r / (float) (CROWN_ICON.length - 1)));
                    g.fillRect(x0 + c * scale, y0 + r * scale, scale, scale);
                }
            }
        }
        // The jewel, front and centre on the band.
        g.setColor(JEWEL);
        g.fillRect(x0 + 6 * scale, y0 + 5 * scale, scale, scale);

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
