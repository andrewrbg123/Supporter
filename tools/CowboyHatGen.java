import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the cowboy hat texture and inventory icon for
 * {@code Common/Items/Armor/Supporter_Hat_Cowboy.blockymodel}.
 *
 * <p>Run with: {@code java tools/CowboyHatGen.java src/main/resources}
 *
 * <p>Second self-authored model, same schema as the crown: root node "Head" is the bone, a
 * 46x2x42 brim box with a 24x10x22 dome on top. The 96x96 sheet layout is authored here and
 * mirrored exactly in the model's per-face offsets:
 *
 * <pre>
 * brim top/bottom    46x42 at (1,1)
 * brim front/back     46x2 at (1,45)
 * brim left/right     42x2 at (1,49)
 * dome front/back    24x10 at (1,53)
 * dome left/right    22x10 at (27,53)
 * dome top/bottom    24x22 at (50,1)
 * </pre>
 *
 * <p>Leather brown rather than a chat colour — a cowboy hat in frost blue stops being a cowboy
 * hat — with the hatband buckle in the supporter amber so it still reads as ours.
 */
public final class CowboyHatGen {

    private static final int TEX = 96;
    private static final int ICON = 64;

    private static final Color LEATHER = new Color(0x9A, 0x6A, 0x33);
    private static final Color LEATHER_DEEP = new Color(0x6B, 0x45, 0x1E);
    private static final Color TRIM = new Color(0x3E, 0x28, 0x10);
    private static final Color BAND = new Color(0x2E, 0x1D, 0x0B);
    private static final Color BUCKLE = new Color(0xFF, 0xAA, 0x00);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(texture(), new File(root, "Common/Items/Armor/Supporter_Hat_Cowboy.png"));
        write(icon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Hat_Cowboy.png"));
        System.out.println("done");
    }

    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Brim top/bottom: leather with a darker rim ring and a subtle radial darkening toward
        // the edge, so the brim reads as curved even though it is a flat box.
        for (int y = 0; y < 42; y++) {
            for (int x = 0; x < 46; x++) {
                float dx = Math.abs(x - 22.5f) / 22.5f;
                float dy = Math.abs(y - 20.5f) / 20.5f;
                float edge = Math.max(dx, dy);
                g.setColor(mix(LEATHER, LEATHER_DEEP, edge * 0.8f));
                g.fillRect(1 + x, 1 + y, 1, 1);
            }
        }
        g.setColor(TRIM);
        g.drawRect(1, 1, 45, 41);

        // Brim edges.
        g.setColor(TRIM);
        g.fillRect(1, 45, 46, 2);
        g.fillRect(1, 49, 42, 2);

        // Dome front/back: leather gradient with the hatband along the bottom three rows and the
        // amber buckle centred on it.
        domeSide(g, 1, 53, 24, true);
        // Dome left/right: same, no buckle.
        domeSide(g, 27, 53, 22, false);

        // Dome top: leather with the classic centre crease painted as a dark front-to-back line.
        for (int y = 0; y < 22; y++) {
            for (int x = 0; x < 24; x++) {
                float dx = Math.abs(x - 11.5f) / 11.5f;
                g.setColor(mix(LEATHER, LEATHER_DEEP, dx * 0.5f));
                g.fillRect(50 + x, 1 + y, 1, 1);
            }
        }
        g.setColor(TRIM);
        g.fillRect(50 + 11, 1, 2, 22);
        g.drawRect(50, 1, 23, 21);

        g.dispose();
        return img;
    }

    private static void domeSide(Graphics2D g, int x, int y, int w, boolean buckle) {
        for (int row = 0; row < 10; row++) {
            g.setColor(mix(LEATHER, LEATHER_DEEP, row / 9f));
            g.fillRect(x, y + row, w, 1);
        }
        g.setColor(BAND);
        g.fillRect(x, y + 7, w, 3);
        if (buckle) {
            g.setColor(BUCKLE);
            g.fillRect(x + (w / 2) - 1, y + 7, 3, 3);
        }
    }

    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // Side-on silhouette: dome with a hatband, then the wide brim with upturned tips.
        // Dome.
        for (int y = 16; y < 34; y++) {
            float t = (y - 16) / 17f;
            int halfWidth = 10 + (int) (t * 2);
            g.setColor(mix(LEATHER, LEATHER_DEEP, t * 0.4f));
            g.fillRect((ICON / 2) - halfWidth, y, halfWidth * 2, 1);
        }
        // Hatband with amber buckle.
        g.setColor(BAND);
        g.fillRect((ICON / 2) - 12, 30, 24, 4);
        g.setColor(BUCKLE);
        g.fillRect((ICON / 2) - 2, 30, 4, 4);
        // Brim: wide bar with upturned edges.
        g.setColor(LEATHER_DEEP);
        g.fillRect((ICON / 2) - 24, 34, 48, 5);
        g.fillRect((ICON / 2) - 26, 30, 4, 7);
        g.fillRect((ICON / 2) + 22, 30, 4, 7);
        g.setColor(TRIM);
        g.fillRect((ICON / 2) - 24, 38, 48, 1);

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
