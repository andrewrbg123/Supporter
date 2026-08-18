import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the texture and icon for the shades model — the one survivor of a three-accessory
 * batch.
 *
 * <p>Run with: {@code java tools/AccessoriesGen.java src/main/resources}
 *
 * <p><b>Why the chain and earrings were built, live-tested and DROPPED - the lesson:</b>
 * body-hugging accessories are skin-dependent. The chain hung at a fixed offset from the Chest
 * bone, but what occupies that space varies per player - cosmetic overtops are extra geometry
 * layered over the body, and the chain sat inside the test player jacket with its medallion
 * peeking out at belt height. Ear TYPES vary the same way, so fixed-offset hoops sat inside
 * pointed ears and would float beside round ones. Things that sit ON TOP of everything - hats,
 * capes, shoes, glasses - fit every skin; things that hug the body fit exactly one.
 *
 * <p>Shades: black lenses with a glint, amber frame and temples, matching the supporter accent.
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

    private static void write(BufferedImage img, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("could not create " + parent);
        }
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
