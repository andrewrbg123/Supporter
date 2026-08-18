import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the supporter cape texture and its inventory icon.
 *
 * <p>Run with: {@code java tools/CapeTextureGen.java src/main/resources}
 *
 * <p><b>96x96, because that is what the cape geometry expects.</b> The item points at the vanilla
 * {@code Items/Back/Cape_Long.blockymodel} — geometry that already ships with every client, so no
 * modelling is needed and nothing of anyone else's art is redistributed. Its texture sheet is
 * 96x96; a different size would map somewhere unpredictable.
 *
 * <p>It has to live under {@code Items/} specifically: the asset validator rejects an item whose
 * model is outside [Blocks/, Items/, Resources/, NPC/, VFX/, Consumable/], and a rejected asset
 * does not degrade — it aborts the entire server boot.
 *
 * <p><b>Deliberately near-uniform.</b> Without the UV map, any detail placed at a specific spot
 * lands somewhere unknown on the cape. A vertical gradient with an edge trim reads as intentional
 * wherever the seams fall, which is the right first version: get it in game, see where the faces
 * actually are, then decorate. Drawing an emblem before knowing the UVs is how you get a crest on
 * the inside of a hem.
 *
 * <p>Colours come from the server's own palette rather than being invented — the amber matches
 * {@code tagColorHex}, so the cape, the chat tag and the panel are the same colour by intent.
 */
public final class CapeTextureGen {

    /**
     * 96x96, matching Items/Back/Cape_Long_Texture.png.
     *
     * <p>The size is dictated by the geometry we point at, not chosen. Get it wrong and the UVs
     * map somewhere unintended.
     */
    private static final int TEX_W = 96;
    private static final int TEX_H = 96;
    private static final int ICON = 64;

    /** Same amber as tagColorHex, the chat tag and the supporter panel accent. */
    private static final Color AMBER = new Color(0xFF, 0xAA, 0x00);
    private static final Color AMBER_DEEP = new Color(0xC9, 0x7C, 0x2A);
    private static final Color TRIM = new Color(0x4A, 0x33, 0x12);
    private static final Color HIGHLIGHT = new Color(0xFF, 0xD1, 0x6B);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(texture(), new File(root, "Common/Items/Armor/Supporter_Cape.png"));
        write(icon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Cape.png"));
        System.out.println("done");
    }

    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX_W, TEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Vertical gradient, light at the shoulders and deep at the hem. Painted pixel rows
        // rather than with GradientPaint so the result is exact and does not depend on
        // interpolation settings.
        for (int y = 0; y < TEX_H; y++) {
            float t = (float) y / (TEX_H - 1);
            g.setColor(mix(AMBER, AMBER_DEEP, t));
            g.fillRect(0, y, TEX_W, 1);
        }

        // Two-pixel trim around the whole sheet. Every face of the cape borders the sheet edge
        // somewhere, so this reads as a hem no matter how the UVs are arranged.
        g.setColor(TRIM);
        g.fillRect(0, 0, TEX_W, 2);
        g.fillRect(0, TEX_H - 2, TEX_W, 2);
        g.fillRect(0, 0, 2, TEX_H);
        g.fillRect(TEX_W - 2, 0, 2, TEX_H);

        // A single highlight line near the top, so the cape is not a flat wash of colour.
        g.setColor(HIGHLIGHT);
        g.fillRect(2, 6, TEX_W - 4, 1);

        g.dispose();
        return img;
    }

    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // A cape silhouette: narrow at the collar, wider at the hem, with a notch cut out of the
        // bottom. Blocky on purpose — antialiasing off, so it sits alongside the game's own icons.
        int top = 10;
        int bottom = ICON - 8;
        for (int y = top; y < bottom; y++) {
            float t = (float) (y - top) / (bottom - top);
            int halfWidth = (int) (10 + (14 * t));
            int x0 = (ICON / 2) - halfWidth;
            int width = halfWidth * 2;

            // The notch: a V cut into the bottom third so the hem reads as cloth, not a slab.
            int notch = 0;
            if (t > 0.72f) {
                notch = (int) ((t - 0.72f) / 0.28f * halfWidth);
            }
            g.setColor(mix(AMBER, AMBER_DEEP, t));
            if (notch <= 0) {
                g.fillRect(x0, y, width, 1);
            } else {
                g.fillRect(x0, y, halfWidth - notch, 1);
                g.fillRect(ICON / 2 + notch, y, halfWidth - notch, 1);
            }
        }

        // Collar.
        g.setColor(TRIM);
        g.fillRect((ICON / 2) - 11, top - 2, 22, 3);
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
