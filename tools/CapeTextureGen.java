import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the supporter cape textures and their inventory icons — one per design.
 *
 * <p>Run with: {@code java tools/CapeTextureGen.java src/main/resources}
 *
 * <p><b>96x96, because that is what the cape geometry expects.</b> The items point at the vanilla
 * {@code Items/Back/Cape_Long.blockymodel} — geometry that already ships with every client, so no
 * modelling is needed and nothing of anyone else's art is redistributed. It has to live under
 * {@code Items/} specifically: the asset validator rejects an item whose model is outside
 * [Blocks/, Items/, Resources/, NPC/, VFX/, Consumable/], and a rejected asset does not degrade —
 * it aborts the entire server boot.
 *
 * <p><b>The emblem is placed from the model's real UV map, not guessed.</b> Walking the
 * blockymodel's quads gives exactly three visible segments:
 *
 * <pre>
 * Cape1  36x35 at (40,10)   — the main back panel
 * Cape2  43x26 at (43,45)   — middle sway segment
 * Cape3  44x24 at (44,71)   — bottom sway segment
 * </pre>
 *
 * <p>Each quad DECLARES the UV of its inner face; the outer face — the one players see — is
 * mirrored to the LEFT of that offset. The vanilla texture paints all its art in the left half
 * of the sheet for exactly this reason, and the first emblem attempt proved it the hard way by
 * rendering a crown on the cape lining. The crown therefore sits in the outer region of Cape1,
 * centred on the upper back.
 *
 * <p><b>Six designs, one per allowed chat colour.</b> The palette comes from the plugin's own
 * {@code allowedChatColors} rather than being invented, so a supporter can match their cape to
 * their chat colour and the two systems stay one family: amber, frost, rose, jade, gold, ember.
 */
public final class CapeTextureGen {

    /** Dictated by Cape_Long.blockymodel's texture sheet. Not a choice. */
    private static final int TEX_W = 96;
    private static final int TEX_H = 96;
    private static final int ICON = 64;

    /**
     * The main back panel: quad Cape1, 36x35, declared UV offset (40,10).
     *
     * <p><b>The declared offset is the INNER face.</b> The face players actually see — the
     * outside of the cape — samples the mirrored region to the LEFT of the offset, x4..40.
     * Proven two ways: the vanilla Cape_Long_Texture paints all of its art in the left half of
     * the sheet, and a crown drawn at the declared offset rendered only as three small marks
     * where its top rows overlapped the collar strip — the rest was on the cape lining, facing
     * the player back.
     */
    private static final int PANEL_X = 40;
    private static final int PANEL_Y = 10;
    private static final int PANEL_W = 36;
    private static final int PANEL_H = 35;
    /** Left edge of the OUTER face region: mirrored across the declared offset. */
    private static final int OUTER_X = PANEL_X - PANEL_W;

    /**
     * The crown, 13x9 cells, drawn at scale 2 (26x18 pixels) on the back panel. Outlined dark and
     * filled bright, so it reads against every base colour.
     */
    private static final String[] CROWN = {
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

    /** name, base (light) colour, deep colour. Bases are the six allowedChatColors. */
    private record Design(String name, Color light, Color deep) { }

    private static final Design[] DESIGNS = {
        // Amber keeps the original file name (no suffix) so capes already in players' inventories
        // keep resolving — an item id, once in the wild, is permanent.
        new Design("amber", new Color(0xFFAA00), new Color(0xC97C2A)),
        new Design("frost", new Color(0x55FFFF), new Color(0x2A7C9C)),
        new Design("rose", new Color(0xFF55FF), new Color(0x9C2A8C)),
        new Design("jade", new Color(0x55FF55), new Color(0x2A9C4A)),
        new Design("gold", new Color(0xFFFF55), new Color(0xC9A82A)),
        new Design("ember", new Color(0xFF5555), new Color(0x9C2A2A)),
    };

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "src/main/resources";
        for (Design d : DESIGNS) {
            String suffix = d.name.equals("amber") ? "" : "_" + cap(d.name);
            write(texture(d), new File(root, "Common/Items/Armor/Supporter_Cape" + suffix + ".png"));
            write(icon(d), new File(root, "Common/Icons/ItemsGenerated/Supporter_Cape" + suffix + ".png"));
        }
        System.out.println("done");
    }

    private static BufferedImage texture(Design d) {
        Color trim = mix(d.deep, Color.BLACK, 0.55f);
        Color highlight = mix(d.light, Color.WHITE, 0.45f);

        BufferedImage img = new BufferedImage(TEX_W, TEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Vertical gradient, light at the shoulders and deep at the hem, spanning the full sheet
        // so all three cape segments shade continuously.
        for (int y = 0; y < TEX_H; y++) {
            float t = (float) y / (TEX_H - 1);
            g.setColor(mix(d.light, d.deep, t));
            g.fillRect(0, y, TEX_W, 1);
        }

        // Hem: the bottom two rows fall inside Cape3's UV region, so this renders as a dark
        // band along the bottom edge of the cape.
        g.setColor(trim);
        g.fillRect(0, TEX_H - 2, TEX_W, 2);

        // Collar accent, spanning both collar faces: the outer collar mirrors to x10..40, the
        // inner strip is x40..70.
        g.setColor(highlight);
        g.fillRect(10, PANEL_Y + 1, 60, 1);

        // The crown goes on the OUTER face region — see OUTER_X. Drawing it at the declared
        // offset paints the lining instead, which is how the first build put the crown on the
        // inside of the cape.
        drawCrown(g, OUTER_X + (PANEL_W - CROWN[0].length() * 2) / 2,
                PANEL_Y + (PANEL_H - CROWN.length * 2) / 2 + 1, 2, trim, highlight);

        g.dispose();
        return img;
    }

    private static BufferedImage icon(Design d) {
        Color trim = mix(d.deep, Color.BLACK, 0.55f);
        Color highlight = mix(d.light, Color.WHITE, 0.45f);

        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // Cape silhouette: narrow at the collar, wider at the hem, with a V notch in the bottom
        // third so the hem reads as cloth. Blocky on purpose, to sit alongside the game's icons.
        int top = 10;
        int bottom = ICON - 8;
        for (int y = top; y < bottom; y++) {
            float t = (float) (y - top) / (bottom - top);
            int halfWidth = (int) (10 + (14 * t));
            int x0 = (ICON / 2) - halfWidth;

            int notch = 0;
            if (t > 0.72f) {
                notch = (int) ((t - 0.72f) / 0.28f * halfWidth);
            }
            g.setColor(mix(d.light, d.deep, t));
            if (notch <= 0) {
                g.fillRect(x0, y, halfWidth * 2, 1);
            } else {
                g.fillRect(x0, y, halfWidth - notch, 1);
                g.fillRect(ICON / 2 + notch, y, halfWidth - notch, 1);
            }
        }

        g.setColor(trim);
        g.fillRect((ICON / 2) - 11, top - 2, 22, 3);

        // The same crown, scale 1, on the upper half so each icon names its design at a glance.
        drawCrown(g, (ICON - CROWN[0].length()) / 2, 22, 1, trim, highlight);

        g.dispose();
        return img;
    }

    /** Paints the crown: an outline pass one pixel out in all directions, then the fill. */
    private static void drawCrown(Graphics2D g, int x, int y, int scale,
                                  Color outline, Color fill) {
        g.setColor(outline);
        for (int r = 0; r < CROWN.length; r++) {
            for (int c = 0; c < CROWN[r].length(); c++) {
                if (CROWN[r].charAt(c) == 'X') {
                    g.fillRect(x + c * scale - 1, y + r * scale - 1, scale + 2, scale + 2);
                }
            }
        }
        g.setColor(fill);
        for (int r = 0; r < CROWN.length; r++) {
            for (int c = 0; c < CROWN[r].length(); c++) {
                if (CROWN[r].charAt(c) == 'X') {
                    g.fillRect(x + c * scale, y + r * scale, scale, scale);
                }
            }
        }
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + ((b.getRed() - a.getRed()) * t)),
                Math.round(a.getGreen() + ((b.getGreen() - a.getGreen()) * t)),
                Math.round(a.getBlue() + ((b.getBlue() - a.getBlue()) * t)));
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
