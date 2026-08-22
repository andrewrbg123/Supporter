import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Generates the gold bunny's body texture — the first SERVER-EXCLUSIVE creature skin.
 *
 * <p>Run with: {@code java tools/GoldBunnyGen.java src/main/resources}
 *
 * <p><b>This is the cape principle applied to a creature.</b> The pet's model asset
 * ({@code Server/Models/Supporter/Supporter_Gold_Bunny.json}) points at the vanilla bunny's
 * geometry and all forty-three of its animations — which every client already has, so nothing
 * of anybody else's art is redistributed and no animation had to be authored — and swaps in
 * this texture, which is entirely our own pixels. The result is a creature that exists only on
 * this server.
 *
 * <p><b>128x96, and that is not a choice.</b> It is the size of the geometry's UV map; a
 * texture of any other size lands its detail somewhere other than where the model samples.
 *
 * <p>Deliberately painted WITHOUT reference to where the vanilla texture puts each body part.
 * That is a spike shortcut, not a style: this build exists to answer whether the model loader
 * scans plugin asset packs at all, and a brushed-gold sheet answers that from any angle. Once
 * the loader is known to work, the UV regions are worth reading off the model properly so the
 * ears, eyes and belly can be picked out — the same discipline the cape needed before its
 * crown stopped landing on the lining.
 */
public final class GoldBunnyGen {

    /** Dictated by the vanilla bunny geometry's UV map. Not a choice. */
    private static final int W = 128;
    private static final int H = 96;

    private static final Color GOLD_LIGHT = new Color(0xFF, 0xDC, 0x8A);
    private static final Color GOLD = new Color(0xE8, 0xB0, 0x3C);
    private static final Color GOLD_DEEP = new Color(0xB5, 0x7C, 0x1E);
    private static final Color GOLD_SHADOW = new Color(0x7A, 0x51, 0x12);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(texture(), new File(root, "Common/NPC/Supporter/Gold_Bunny.png"));
        System.out.println("done");
    }

    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Fixed seed: the sheet must not change between runs, or every rebuild would ship a
        // different bunny and no live comparison would mean anything.
        Random rnd = new Random(20260822L);

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // Vertical gradient: light along the top of each sheet row, deeper below. With
                // the UV layout unread, a gradient at least guarantees the model is never a
                // single flat colour from any angle.
                float t = (float) y / (H - 1);
                Color base = t < 0.5f
                        ? mix(GOLD_LIGHT, GOLD, t * 2f)
                        : mix(GOLD, GOLD_DEEP, (t - 0.5f) * 2f);

                // Brushed streaks: horizontal bands of slightly varied tone, which is what
                // makes metal read as metal rather than as plastic.
                if ((y % 4) == 0) {
                    base = mix(base, GOLD_LIGHT, 0.25f);
                } else if ((y % 7) == 0) {
                    base = mix(base, GOLD_SHADOW, 0.18f);
                }
                if (rnd.nextInt(11) == 0) {
                    base = mix(base, GOLD_LIGHT, 0.2f);
                }
                g.setColor(base);
                g.fillRect(x, y, 1, 1);
            }
        }
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
