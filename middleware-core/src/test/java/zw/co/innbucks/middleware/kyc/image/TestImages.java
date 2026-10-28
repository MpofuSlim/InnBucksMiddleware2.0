package zw.co.innbucks.middleware.kyc.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Synthetic images built in memory — no image fixture is ever committed. */
public final class TestImages {

    private TestImages() {
    }

    public static BufferedImage picture(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        try {
            for (int y = 0; y < height; y += 16) {
                for (int x = 0; x < width; x += 16) {
                    g.setColor(new Color((x * 7) % 256, (y * 5) % 256, ((x + y) * 3) % 256));
                    g.fillRect(x, y, 16, 16);
                }
            }
            // No text: drawing a string needs fonts, which a headless CI JRE may lack.
            g.setColor(Color.BLACK);
            g.drawLine(0, 0, width - 1, height - 1);
        } finally {
            g.dispose();
        }
        return image;
    }

    public static byte[] jpeg(int width, int height) {
        return jpeg(picture(width, height, BufferedImage.TYPE_INT_RGB), false);
    }

    public static byte[] jpeg(BufferedImage image, boolean progressive) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(out);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (progressive) {
                    param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
                }
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(picture(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Splices an EXIF APP1 segment carrying {@code payload} right after SOI. */
    public static byte[] withExif(byte[] jpeg, String payload) {
        byte[] body = ("Exif\0\0" + payload).getBytes(StandardCharsets.ISO_8859_1);
        int length = body.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(body, 0, body.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Rewrites the SOF0 header's dimensions, leaving the (small) image data as it was. */
    public static byte[] withDeclaredSize(byte[] jpeg, int width, int height) {
        byte[] out = jpeg.clone();
        for (int i = 2; i < out.length - 9; i++) {
            if ((out[i] & 0xFF) == 0xFF && (out[i + 1] & 0xFF) == 0xC0) {
                out[i + 5] = (byte) (height >> 8);
                out[i + 6] = (byte) height;
                out[i + 7] = (byte) (width >> 8);
                out[i + 8] = (byte) width;
                return out;
            }
        }
        throw new IllegalArgumentException("no SOF0 in the test JPEG");
    }

    public static BufferedImage decode(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
