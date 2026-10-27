import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Draws the desktop app's icon from the phone's launcher icon
 * (app/src/main/res/drawable/ic_launcher_*.xml): the N as one soft ink
 * stroke and the orange "now playing" dot, on the warm ivory, here on the
 * rounded tile a Windows icon stands on.
 *
 * Run from the repository root:  java desktop/icons/MakeIcons.java
 * It writes the window's PNGs into the app's resources and nexfy.ico, which
 * the installer and the .exe carry.
 */
public class MakeIcons {
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        File resources = new File("desktop/src/main/resources/icons");
        resources.mkdirs();
        byte[][] pngs = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) {
            BufferedImage image = draw(SIZES[i]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            pngs[i] = out.toByteArray();
            Files.write(new File(resources, "nexfy-" + SIZES[i] + ".png").toPath(), pngs[i]);
        }
        Files.write(new File("desktop/icons/nexfy.ico").toPath(), ico(pngs));
    }

    /** The icon at [size] pixels a side. */
    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // The tile: a small margin all round, corners as round as the
        // system's own icons, the ivory a touch lighter at the top.
        double margin = size * .06;
        double side = size - 2 * margin;
        RoundRectangle2D tile = new RoundRectangle2D.Double(margin, margin, side, side, side * .44, side * .44);
        g.setPaint(new GradientPaint(0, (float) margin, new Color(0xF7F4EE), 0, (float) (margin + side), new Color(0xEDE7DC)));
        g.fill(tile);
        // A hairline so the pale tile keeps its edge on a light taskbar.
        if (size >= 24) {
            g.setColor(new Color(0, 0, 0, 28));
            g.setStroke(new BasicStroke((float) Math.max(1, size / 128.0)));
            g.draw(tile);
        }

        // The launcher shows the middle 72 of the 108 units the mark is
        // drawn on, and the mark is scaled by .7 around the centre there.
        AffineTransform mark = new AffineTransform();
        mark.translate(margin, margin);
        mark.scale(side / 72.0, side / 72.0);
        mark.translate(-18, -18);
        mark.translate(54, 54);
        mark.scale(.7, .7);
        mark.translate(-54, -54);
        g.transform(mark);

        // M37,71 V41 c0,-4 4,-5.5 6.5,-2.5 l21,31 c2.5,3 6.5,1.5 6.5,-2.5 V37
        Path2D n = new Path2D.Double();
        n.moveTo(37, 71);
        n.lineTo(37, 41);
        n.curveTo(37, 37, 41, 35.5, 43.5, 38.5);
        n.lineTo(64.5, 69.5);
        n.curveTo(67, 72.5, 71, 71, 71, 67);
        n.lineTo(71, 37);
        // Small icons get a slightly bolder stroke so the N survives 16 px.
        float stroke = size <= 24 ? 9.5f : 8f;
        g.setColor(new Color(0x141414));
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(n);
        g.setColor(new Color(0xFF5A36));
        g.fill(new Ellipse2D.Double(79 - 5.5, 31 - 5.5, 11, 11));
        g.dispose();
        return image;
    }

    /** An .ico holding each size as PNG, which every Windows since Vista reads. */
    private static byte[] ico(byte[][] pngs) {
        int offset = 6 + 16 * pngs.length;
        int total = offset;
        for (byte[] png : pngs) total += png.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) pngs.length);
        for (int i = 0; i < pngs.length; i++) {
            int s = SIZES[i];
            b.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s));
            b.put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32);
            b.putInt(pngs[i].length).putInt(offset);
            offset += pngs[i].length;
        }
        for (byte[] png : pngs) b.put(png);
        return b.array();
    }
}
