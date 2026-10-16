import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Renders the status-bar mode icons and the launcher icon. Usage: java GenIcons.java <res dir> */
public class GenIcons {
    static final String[][] STATUS = {
        {"st_jyut", "粵"}, {"st_cangjie", "倉"}, {"st_quick", "速"}, {"st_stroke", "筆"}, {"st_custom", "自"},
        {"st_abc", "Ab"}, {"st_lower", "ab"}, {"st_upper", "AB"}, {"st_number", "12"},
    };
    static final String[][] DENSITIES = {{"mdpi", "1"}, {"hdpi", "1.5"}, {"xhdpi", "2"}, {"xxhdpi", "3"}};

    public static void main(String[] args) throws Exception {
        File res = new File(args[0]);
        String family = pickFont();
        for (String[] d : DENSITIES) {
            double scale = Double.parseDouble(d[1]);
            File dir = new File(res, "drawable-" + d[0]);
            dir.mkdirs();
            for (String[] s : STATUS) {
                int size = (int) Math.round(24 * scale);
                write(render(size, s[1], family, null, Color.WHITE, 0), new File(dir, s[0] + ".png"));
            }
            int size = (int) Math.round(48 * scale);
            write(render(size, "九", family, new Color(0x1A73E8), Color.WHITE, size / 5), new File(dir, "ic_launcher.png"));
        }
    }

    static String pickFont() {
        for (String f : new String[] {"PingFang HK", "PingFang TC", "Heiti TC", "Hiragino Sans GB", "STHeiti"}) {
            Font font = new Font(f, Font.BOLD, 12);
            if (font.canDisplay('粵') && font.getFamily().equals(f)) return f;
        }
        return Font.SANS_SERIF;
    }

    static BufferedImage render(int size, String label, String family, Color bg, Color fg, int radius) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (bg != null) {
            g.setColor(bg);
            int inset = size / 12;
            g.fillRoundRect(inset, inset, size - 2 * inset, size - 2 * inset, radius, radius);
        }
        double target = bg != null ? size * 0.62 : size * 0.92;
        int pt = size;
        Font font;
        FontMetrics fm;
        do {
            font = new Font(family, Font.BOLD, pt--);
            fm = g.getFontMetrics(font);
        } while ((fm.stringWidth(label) > target || fm.getAscent() * 0.9 > target) && pt > 4);
        g.setFont(font);
        g.setColor(fg);
        int w = fm.stringWidth(label);
        java.awt.geom.Rectangle2D box = font.createGlyphVector(g.getFontRenderContext(), label).getVisualBounds();
        double y = (size - box.getHeight()) / 2 - box.getY();
        g.drawString(label, (size - w) / 2f, (float) y);
        g.dispose();
        return img;
    }

    static void write(BufferedImage img, File f) throws Exception {
        ImageIO.write(img, "png", f);
    }
}
