import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the trainers texture and inventory icon for
 * {@code Common/Items/Armor/Supporter_Trainers.blockymodel}.
 *
 * <p>Run with: {@code java tools/TrainersGen.java src/main/resources}
 *
 * <p>Third self-authored model, and the first ANIMATED one: leg models have two root bones,
 * {@code L-Calf}/{@code R-Calf}, each holding an {@code L-Foot}/{@code R-Foot} anchor that the
 * walk cycle drives — bone positions copied digit-for-digit from the vanilla Bronze legs model,
 * because those numbers ARE the bone binding. One 15x7x21 box per foot, calves left bare so the
 * player's trousers stay visible.
 *
 * <p>64x64 sheet, authored here and mirrored in the model's per-face offsets:
 *
 * <pre>
 * sides (both)   21x7  at (1,1)
 * front (toe)    15x7  at (1,10)
 * back (heel)    15x7  at (17,10)
 * top            15x21 at (24,19)
 * bottom (sole)  15x21 at (41,19)
 * </pre>
 *
 * <p>Classic trainer: white upper, dark sole, amber side stripe and laces — the stripe in
 * supporter amber, same accent as the cape emblem and the hat buckle.
 */
public final class TrainersGen {

    private static final int TEX = 64;
    private static final int ICON = 64;

    private static final Color UPPER = new Color(0xF2, 0xF0, 0xEA);
    private static final Color UPPER_SHADE = new Color(0xCF, 0xCB, 0xC0);
    private static final Color SOLE = new Color(0x3A, 0x38, 0x34);
    private static final Color SOLE_EDGE = new Color(0x5A, 0x57, 0x50);
    private static final Color STRIPE = new Color(0xFF, 0xAA, 0x00);
    private static final Color LACE = new Color(0x8A, 0x86, 0x7E);

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        write(texture(), new File(root, "Common/Items/Armor/Supporter_Trainers.png"));
        write(icon(), new File(root, "Common/Icons/ItemsGenerated/Supporter_Trainers.png"));
        System.out.println("done");
    }

    /**
     * v2 layout, three boxes per foot instead of one painted brick:
     *
     * <pre>
     * sole side    23x3  at (1,1)     sole ends   16x3  at (1,6)
     * sole tread   16x23 at (1,11)    sole top    16x23 at (1,36)
     * upper side   19x5  at (26,1)    upper toe   14x5  at (26,8)
     * upper heel   14x5  at (41,8)    upper top   14x19 at (47,1)
     * collar side   8x3  at (26,15)   collar ends 14x3  at (36,15)
     * collar top   14x8  at (26,20)
     * </pre>
     */
    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Sole: cream midsole over a dark outsole — the two-tone edge is what makes a chunky
        // sneaker sole read as one.
        soleStrip(g, 1, 1, 23);
        soleStrip(g, 1, 6, 16);
        // Tread underside.
        g.setColor(SOLE);
        g.fillRect(1, 11, 16, 23);
        g.setColor(SOLE_EDGE);
        for (int row = 0; row < 5; row++) {
            g.fillRect(2, 13 + (row * 4), 14, 1);
        }
        // Sole top, hidden under the upper.
        g.setColor(SOLE);
        g.fillRect(1, 36, 16, 23);

        // Upper side: white with the amber swoosh sweeping the middle rows.
        for (int y = 0; y < 5; y++) {
            g.setColor(mix(UPPER, UPPER_SHADE, y / 4f));
            g.fillRect(26, 1 + y, 19, 1);
        }
        g.setColor(STRIPE);
        g.fillRect(28, 3, 12, 1);
        g.fillRect(36, 2, 6, 1);

        // Toe: white with two rows of perforation dots.
        for (int y = 0; y < 5; y++) {
            g.setColor(mix(UPPER, UPPER_SHADE, y / 4f));
            g.fillRect(26, 8 + y, 14, 1);
        }
        g.setColor(UPPER_SHADE);
        for (int dot = 0; dot < 4; dot++) {
            g.fillRect(28 + (dot * 3), 9, 1, 1);
            g.fillRect(29 + (dot * 3), 11, 1, 1);
        }

        // Heel: white with a vertical amber pull tab.
        for (int y = 0; y < 5; y++) {
            g.setColor(mix(UPPER, UPPER_SHADE, y / 4f));
            g.fillRect(41, 8 + y, 14, 1);
        }
        g.setColor(STRIPE);
        g.fillRect(41 + 6, 8, 2, 4);

        // Upper top: white with lace crosses.
        for (int y = 0; y < 19; y++) {
            g.setColor(mix(UPPER, UPPER_SHADE, y / 18f));
            g.fillRect(47, 1 + y, 14, 1);
        }
        g.setColor(LACE);
        for (int row = 0; row < 4; row++) {
            g.fillRect(47 + 3, 1 + 4 + (row * 3), 8, 1);
        }

        // Collar: padded cream with the dark ankle opening on top.
        g.setColor(UPPER_SHADE);
        g.fillRect(26, 15, 8, 3);
        g.fillRect(36, 15, 14, 3);
        g.setColor(SOLE_EDGE);
        g.fillRect(26, 15, 8, 1);
        g.fillRect(36, 15, 14, 1);
        g.setColor(SOLE);
        g.fillRect(26, 20, 14, 8);
        g.setColor(UPPER_SHADE);
        g.drawRect(26, 20, 13, 7);

        g.dispose();
        return img;
    }

    /** Sole side/end strip: cream midsole rows over one dark outsole row. */
    private static void soleStrip(Graphics2D g, int x, int y, int w) {
        g.setColor(UPPER_SHADE);
        g.fillRect(x, y, w, 2);
        g.setColor(SOLE);
        g.fillRect(x, y + 2, w, 1);
    }

    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // Side-on trainer silhouette: low heel rising to an ankle collar, amber swoosh, thick
        // sole. Toe pointing right.
        // Upper.
        for (int y = 24; y < 40; y++) {
            float t = (y - 24) / 15f;
            int x0 = 10 + (int) ((1 - t) * 14);
            g.setColor(mix(UPPER, UPPER_SHADE, t * 0.5f));
            g.fillRect(x0, y, 54 - x0, 1);
        }
        // Ankle collar.
        g.setColor(UPPER_SHADE);
        g.fillRect(10, 22, 12, 3);
        // Amber stripe.
        g.setColor(STRIPE);
        for (int i = 0; i < 12; i++) {
            g.fillRect(26 + (i * 2), 30 + (i / 3), 2, 3);
        }
        // Laces.
        g.setColor(LACE);
        for (int row = 0; row < 3; row++) {
            g.fillRect(30 + (row * 4), 25 + row, 6, 1);
        }
        // Sole.
        g.setColor(SOLE);
        g.fillRect(8, 40, 50, 5);
        g.setColor(SOLE_EDGE);
        g.fillRect(8, 40, 50, 1);

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
