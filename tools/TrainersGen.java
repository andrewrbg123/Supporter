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

    private static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Sides: white upper shading down, amber stripe through the middle, sole band along the
        // bottom two rows with a lighter edge line above it.
        for (int y = 0; y < 7; y++) {
            g.setColor(mix(UPPER, UPPER_SHADE, y / 6f));
            g.fillRect(1, 1 + y, 21, 1);
        }
        g.setColor(STRIPE);
        g.fillRect(3, 3, 17, 2);
        g.setColor(SOLE_EDGE);
        g.fillRect(1, 6, 21, 1);
        g.setColor(SOLE);
        g.fillRect(1, 7, 21, 1);

        // Toe: white with the sole band.
        face(g, 1, 10, 15);
        // Heel: same, plus a vertical amber tab.
        face(g, 17, 10, 15);
        g.setColor(STRIPE);
        g.fillRect(17 + 6, 10, 3, 4);

        // Top: white with lace crosses on the front half (low z = toe end).
        for (int y = 0; y < 21; y++) {
            g.setColor(mix(UPPER, UPPER_SHADE, y / 20f));
            g.fillRect(24, 19 + y, 15, 1);
        }
        g.setColor(LACE);
        for (int row = 0; row < 3; row++) {
            g.fillRect(24 + 4, 19 + 4 + (row * 3), 7, 1);
        }
        g.setColor(SOLE_EDGE);
        g.drawRect(24, 19, 14, 20);

        // Sole underside: dark with a tread pattern.
        g.setColor(SOLE);
        g.fillRect(41, 19, 15, 21);
        g.setColor(SOLE_EDGE);
        for (int row = 0; row < 5; row++) {
            g.fillRect(42, 21 + (row * 4), 13, 1);
        }

        g.dispose();
        return img;
    }

    /** A 15-wide, 7-tall end face: white upper over the sole band. */
    private static void face(Graphics2D g, int x, int y, int w) {
        for (int row = 0; row < 7; row++) {
            g.setColor(mix(UPPER, UPPER_SHADE, row / 6f));
            g.fillRect(x, y + row, w, 1);
        }
        g.setColor(SOLE_EDGE);
        g.fillRect(x, y + 5, w, 1);
        g.setColor(SOLE);
        g.fillRect(x, y + 6, w, 1);
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
