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
import java.util.Arrays;

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

    /**
     * A hand-built baseline (SOF0) 3-component JPEG of flat grey, written
     * byte by byte because ImageIO never writes a sequential file split
     * across scans. {@code interleaved} = one scan carrying all three
     * components (what cameras write); otherwise one scan PER component —
     * the shape that makes libjpeg buffer the whole image's coefficients in
     * native memory. Every block is DC category 0 + EOB, so the scan data is
     * two bits a block and the file stays tiny whatever its dimensions.
     */
    public static byte[] sequentialScans(int width, int height, boolean interleaved) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        byte[] quant = new byte[65];
        Arrays.fill(quant, 1, 65, (byte) 1);
        segment(out, 0xDB, quant);
        segment(out, 0xC0, new byte[]{8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width,
                3, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0});
        // DC table 0 and AC table 0: one code of length 1 each (category 0 / EOB).
        segment(out, 0xC4, new byte[]{0x00, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x00});
        segment(out, 0xC4, new byte[]{0x10, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x00});
        long blocks = (long) ((width + 7) / 8) * ((height + 7) / 8);
        if (interleaved) {
            segment(out, 0xDA, new byte[]{3, 1, 0x00, 2, 0x00, 3, 0x00, 0, 63, 0});
            scanData(out, blocks * 3 * 2);
        } else {
            for (int component = 1; component <= 3; component++) {
                segment(out, 0xDA, new byte[]{1, (byte) component, 0x00, 0, 63, 0});
                scanData(out, blocks * 2);
            }
        }
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int length = payload.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
    }

    /** {@code bits} zero bits (every code is "0"), padded to a byte with 1-bits. */
    private static void scanData(ByteArrayOutputStream out, long bits) {
        for (long i = 0; i < bits / 8; i++) {
            out.write(0);
        }
        int used = (int) (bits % 8);
        if (used != 0) {
            out.write(0xFF >> used);
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
