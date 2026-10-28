package zw.co.innbucks.middleware.kyc.image;

import zw.co.innbucks.middleware.kyc.image.KycImageRejectedException.Reason;

/**
 * A marker-level walk over a JPEG, done BEFORE any decoder sees the bytes. It
 * reads the frame header (dimensions, encoding) and checks the file is whole,
 * without decoding a pixel.
 *
 * <p>It exists because the decoder is the dangerous part. A header can declare
 * 30000 x 30000 pixels in a few hundred bytes (a decompression bomb that would
 * take the single-replica money path's heap); a progressive JPEG can carry
 * thousands of scans that each re-walk the whole image (CPU for minutes); and
 * the JDK decoder answers a truncated file with a half-grey image and a
 * warning rather than an error. So, before decoding, this requires:
 *
 * <ul>
 *   <li>SOI first, then a well-formed segment chain ending in EOI (a file
 *       with no EOI is truncated);</li>
 *   <li>exactly one frame, and only baseline or extended-sequential Huffman
 *       ({@code SOF0}/{@code SOF1}) at 8-bit precision with 1 or 3
 *       components — what every phone camera writes;</li>
 *   <li>at most one scan per component (the sequential-mode rule), which
 *       bounds decode work to one pass;</li>
 *   <li>a height in the frame header (never deferred to a DNL marker).</li>
 * </ul>
 *
 * Whatever follows EOI is ignored here and dropped by the re-encode.
 */
final class JpegStructure {

    /** Real files carry a dozen or two segments; this only stops a marker flood. */
    static final int MAX_SEGMENTS = 512;

    private JpegStructure() {
    }

    record Frame(int width, int height, int components) {
        long pixels() {
            return (long) width * height;
        }
    }

    static Frame parse(byte[] data) {
        if (data.length < 4 || u8(data, 0) != 0xFF || u8(data, 1) != 0xD8 || u8(data, 2) != 0xFF) {
            throw new KycImageRejectedException(Reason.NOT_JPEG, "the file is not a JPEG");
        }
        int pos = 2;
        int segments = 0;
        int scans = 0;
        Frame frame = null;
        while (true) {
            if (pos >= data.length || u8(data, pos) != 0xFF) {
                throw corrupt("the JPEG ends before its end-of-image marker");
            }
            while (pos < data.length && u8(data, pos) == 0xFF) {
                pos++; // fill bytes before a marker
            }
            if (pos >= data.length) {
                throw corrupt("the JPEG ends inside a marker");
            }
            int marker = u8(data, pos++);
            if (++segments > MAX_SEGMENTS) {
                throw corrupt("the JPEG has too many segments");
            }
            if (marker == 0xD9) { // EOI
                break;
            }
            if (marker == 0x01) { // TEM: no length
                continue;
            }
            if (marker == 0x00 || marker == 0xD8 || marker >= 0xD0 && marker <= 0xD7) {
                throw corrupt("unexpected marker outside a scan");
            }
            if (pos + 2 > data.length) {
                throw corrupt("the JPEG ends inside a segment header");
            }
            int length = (u8(data, pos) << 8) | u8(data, pos + 1);
            if (length < 2 || pos + length > data.length) {
                throw corrupt("a segment runs past the end of the file");
            }
            if (isFrameMarker(marker)) {
                if (frame != null) {
                    throw unsupported("more than one frame");
                }
                frame = readFrame(data, pos, length, marker);
            } else if (marker == 0xDA) { // SOS
                if (frame == null) {
                    throw corrupt("a scan before the frame header");
                }
                if (++scans > frame.components()) {
                    throw unsupported("more scans than a sequential JPEG can have");
                }
                pos = skipEntropyCodedData(data, pos + length);
                continue;
            }
            pos += length;
        }
        if (frame == null || scans == 0) {
            throw corrupt("the JPEG has no image data");
        }
        return frame;
    }

    private static Frame readFrame(byte[] data, int pos, int length, int marker) {
        if (marker != 0xC0 && marker != 0xC1) {
            throw unsupported("only baseline (sequential) JPEGs are accepted; this one is "
                    + (marker == 0xC2 ? "progressive" : "an unsupported encoding"));
        }
        if (length < 8) {
            throw corrupt("the frame header is too short");
        }
        int precision = u8(data, pos + 2);
        int height = (u8(data, pos + 3) << 8) | u8(data, pos + 4);
        int width = (u8(data, pos + 5) << 8) | u8(data, pos + 6);
        int components = u8(data, pos + 7);
        if (length != 8 + 3 * components) {
            throw corrupt("the frame header length does not match its components");
        }
        if (precision != 8) {
            throw unsupported("only 8-bit JPEGs are accepted");
        }
        if (components != 1 && components != 3) {
            throw unsupported("only greyscale or colour (YCbCr) JPEGs are accepted");
        }
        if (width == 0 || height == 0) {
            throw unsupported("the image dimensions are not in the frame header");
        }
        return new Frame(width, height, components);
    }

    /** From the first byte after an SOS header to the next real marker. */
    private static int skipEntropyCodedData(byte[] data, int pos) {
        while (pos < data.length) {
            if (u8(data, pos) == 0xFF) {
                if (pos + 1 >= data.length) {
                    throw corrupt("the JPEG ends inside its image data");
                }
                int next = u8(data, pos + 1);
                if (next == 0x00 || next >= 0xD0 && next <= 0xD7) {
                    pos += 2; // stuffed 0xFF, or a restart marker inside the scan
                    continue;
                }
                return pos; // a marker: back to the segment walk (fill bytes included)
            }
            pos++;
        }
        throw corrupt("the JPEG ends inside its image data");
    }

    /** SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    private static boolean isFrameMarker(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static int u8(byte[] data, int index) {
        return data[index] & 0xFF;
    }

    private static KycImageRejectedException corrupt(String message) {
        return new KycImageRejectedException(Reason.CORRUPT, message);
    }

    private static KycImageRejectedException unsupported(String message) {
        return new KycImageRejectedException(Reason.UNSUPPORTED_ENCODING, message);
    }
}
