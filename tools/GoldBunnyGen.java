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
 * this texture, which is entirely our own pixels. Live-proven 0.29.0: the model-asset loader
 * DOES scan plugin packs, so a creature can exist on this server and nowhere else.
 *
 * <p><b>128x96, and that is not a choice.</b> It is the size of the geometry's UV map; a
 * texture of any other size lands its detail somewhere other than where the model samples.
 *
 * <p><b>How the regions below were found, and where the line is.</b> The vanilla texture was
 * read as a CLASS MAP — every pixel sorted into near-black, pink, near-white or coat, then
 * dumped as ASCII — which shows where the sheet keeps the ears, the eyes and the underside.
 * That is reading a map, and it is the only way to know that the inner ear lives at x43..49.
 * What is NOT done here is sampling their pixels and recolouring them: that would ship a
 * derivative of their art. Instead the map gave RECTANGLES, and every pixel inside them is
 * painted from scratch below. Same rule the capes follow — shared geometry, our own pixels.
 *
 * <p>Region map read off the vanilla sheet:
 *
 * <pre>
 * ears + inner ear   x38..58  y0..19    (dark tips, pink inners)
 * eye                x45..48  y16..18
 * face / nose        x40..47  y39..47
 * underside          x0..37   y28..56, x0..20 y62..79, x34..82 y76..95
 * flank pale         x95..110 y41..55
 * second inner ear   x83..91  y73..88
 * (four smaller pink patches were dropped in 0.29.2 - see ROSE_PARTS)
 * </pre>
 */
public final class GoldBunnyGen {

    /** Dictated by the vanilla bunny geometry's UV map. Not a choice. */
    private static final int W = 128;
    private static final int H = 96;

    private static final Color GOLD_LIGHT = new Color(0xFF, 0xDC, 0x8A);
    private static final Color GOLD = new Color(0xE8, 0xB0, 0x3C);
    private static final Color GOLD_DEEP = new Color(0xB5, 0x7C, 0x1E);
    private static final Color GOLD_SHADOW = new Color(0x7A, 0x51, 0x12);
    /** Pale champagne for the underside — still gold, just lifted. */
    private static final Color CHAMPAGNE = new Color(0xFF, 0xEE, 0xC4);
    private static final Color CHAMPAGNE_DEEP = new Color(0xE6, 0xCE, 0x9A);
    /** Rose gold for the inner ears. */
    private static final Color ROSE = new Color(0xE8, 0x9C, 0x86);
    private static final Color ROSE_DEEP = new Color(0xC2, 0x74, 0x5E);
    /** Dark bronze: eyes and the tipping on the ears. */
    private static final Color BRONZE = new Color(0x3A, 0x28, 0x10);
    private static final Color BRONZE_LIT = new Color(0x6B, 0x4D, 0x22);

    /** x, y, w, h — read off the vanilla sheet's class map, painted from scratch. */
    private static final int[][] UNDERSIDE = {
        {0, 28, 38, 29}, {0, 62, 21, 18}, {34, 76, 49, 20}, {95, 41, 16, 15}, {47, 87, 55, 9},
    };
    /**
     * ONLY the two inner ears (0.29.2). The class map found six pink regions and the first
     * build painted all of them, on the assumption they were ears, nose and paw pads. Four
     * were small and speculative, and at least one landed on the chest — where a filled
     * rectangle reads as a printed pink bib with hard edges rather than as a marking.
     *
     * <p>The lesson is about what a region map does and does not tell you: it says WHERE the
     * sheet keeps a colour, never WHAT that patch is on the animal. The two big regions are
     * unambiguous because a rabbit has two ears and these are the only two patches large
     * enough to be them. Everything smaller was a guess dressed up as data, so it is gone.
     */
    private static final int[][] ROSE_PARTS = {
        {43, 4, 7, 16}, {83, 73, 9, 16},
    };
    private static final int[][] EAR_TIPS = {
        {44, 0, 3, 8}, {52, 0, 7, 8},
    };
    private static final int[][] EYES = {
        {45, 16, 4, 3}, {41, 40, 6, 7},
    };

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

        // Coat: brushed gold over the whole sheet, so any region the map missed still reads as
        // metal rather than as a hole.
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float t = (float) y / (H - 1);
                Color base = t < 0.5f
                        ? mix(GOLD_LIGHT, GOLD, t * 2f)
                        : mix(GOLD, GOLD_DEEP, (t - 0.5f) * 2f);
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

        // Underside: champagne, brushed the same way so it reads as the same metal catching
        // more light rather than as a different material.
        for (int[] r : UNDERSIDE) {
            for (int y = r[1]; y < Math.min(H, r[1] + r[3]); y++) {
                for (int x = r[0]; x < Math.min(W, r[0] + r[2]); x++) {
                    Color c = ((y % 5) == 0) ? CHAMPAGNE_DEEP : CHAMPAGNE;
                    if (rnd.nextInt(9) == 0) {
                        c = mix(c, GOLD_LIGHT, 0.3f);
                    }
                    g.setColor(c);
                    g.fillRect(x, y, 1, 1);
                }
            }
        }

        // Inner ears: rose gold, darker toward the edges of each patch so they
        // read as recessed rather than painted on.
        for (int[] r : ROSE_PARTS) {
            for (int y = r[1]; y < Math.min(H, r[1] + r[3]); y++) {
                for (int x = r[0]; x < Math.min(W, r[0] + r[2]); x++) {
                    float ex = Math.abs((x - r[0]) - ((r[2] - 1) / 2f)) / Math.max(1f, r[2] / 2f);
                    float ey = Math.abs((y - r[1]) - ((r[3] - 1) / 2f)) / Math.max(1f, r[3] / 2f);
                    g.setColor(mix(ROSE, ROSE_DEEP, Math.min(1f, Math.max(ex, ey) * 0.9f)));
                    g.fillRect(x, y, 1, 1);
                }
            }
        }

        // Ear tips: bronze, fading into the gold below so the tipping looks dipped rather than
        // stamped on.
        for (int[] r : EAR_TIPS) {
            for (int y = r[1]; y < Math.min(H, r[1] + r[3]); y++) {
                float down = (float) (y - r[1]) / Math.max(1, r[3] - 1);
                for (int x = r[0]; x < Math.min(W, r[0] + r[2]); x++) {
                    g.setColor(mix(BRONZE, GOLD_DEEP, down));
                    g.fillRect(x, y, 1, 1);
                }
            }
        }

        // Eyes: bronze with a single lit pixel. At this scale one highlight is the difference
        // between an eye and a smudge.
        for (int[] r : EYES) {
            g.setColor(BRONZE);
            g.fillRect(r[0], r[1], r[2], r[3]);
            g.setColor(BRONZE_LIT);
            g.fillRect(r[0] + r[2] - 2, r[1], 1, 1);
        }

        g.dispose();
        return img;
    }

    private static Color mix(Color a, Color b, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(a.getRed() + ((b.getRed() - a.getRed()) * u)),
                Math.round(a.getGreen() + ((b.getGreen() - a.getGreen()) * u)),
                Math.round(a.getBlue() + ((b.getBlue() - a.getBlue()) * u)));
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
