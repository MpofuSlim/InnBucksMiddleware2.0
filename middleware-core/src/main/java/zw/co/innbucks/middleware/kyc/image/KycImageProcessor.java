package zw.co.innbucks.middleware.kyc.image;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;
import zw.co.innbucks.middleware.kyc.KycFileKind;
import zw.co.innbucks.middleware.kyc.KycProperties;
import zw.co.innbucks.middleware.kyc.image.KycImageRejectedException.Reason;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns an uploaded file into a JPEG we are willing to keep: pixels only.
 *
 * <ol>
 *   <li>Before the caller reads the body, {@link #admit()} takes one of a
 *       few cell-wide upload places, or refuses at once (503
 *       {@code kyc_upload_busy}): at most that many upload bodies are ever in
 *       the heap, whatever Tomcat's thread count or the rate-limit bursts
 *       allow.</li>
 *   <li>{@link JpegStructure} walks the markers: a JPEG by its BYTES (never
 *       the Content-Type), whole, baseline, with its dimensions in the
 *       header. The pixel cap and the legibility minimum are checked on those
 *       declared dimensions, so a decompression bomb is refused before a
 *       single pixel is allocated.</li>
 *   <li>Decode under a small cell-wide {@link Semaphore} — the cell is one
 *       replica in a 768 MB container that also moves money — through the
 *       JPEG reader only (never {@code ImageIO.read}, which sniffs any
 *       format), over in-memory streams with {@code ImageIO.setUseCache(false)}
 *       besides: nothing is ever spooled to a temp file in plaintext. Large
 *       images are subsampled DURING decode. Any decoder warning (the JDK
 *       reports truncation as a warning, not an error) refuses.</li>
 *   <li>Scale down to the kind's longest side (documents 1600, selfie 1080)
 *       and re-encode from pixels at an explicit quality. Nothing of the
 *       original survives: no EXIF (location, device), no ICC or XMP, no
 *       thumbnail, nothing hidden after the image data. EXIF orientation is
 *       dropped, not applied — the app bakes orientation into the pixels.</li>
 * </ol>
 *
 * Never logs bytes.
 */
@Component
@ConditionalOnKycEnabled
public class KycImageProcessor {

    private final KycProperties.Image limits;
    private final Semaphore uploadSlots;
    private final Semaphore decodeSlots;

    @Autowired
    public KycImageProcessor(KycProperties properties) {
        this(properties.image());
    }

    public KycImageProcessor(KycProperties.Image limits) {
        this(limits, new Semaphore(limits.maxConcurrentUploads()), new Semaphore(limits.maxConcurrentDecodes(), true));
    }

    KycImageProcessor(KycProperties.Image limits, Semaphore decodeSlots) {
        this(limits, new Semaphore(limits.maxConcurrentUploads()), decodeSlots);
    }

    KycImageProcessor(KycProperties.Image limits, Semaphore uploadSlots, Semaphore decodeSlots) {
        this.limits = limits;
        this.uploadSlots = uploadSlots;
        this.decodeSlots = decodeSlots;
        // Belt and braces: the streams below are in-memory already, but any
        // other ImageIO use in this JVM must not spool to java.io.tmpdir either.
        ImageIO.setUseCache(false);
    }

    /**
     * Takes one of the cell-wide upload places for as long as the caller holds
     * an upload body: call it BEFORE reading the body, and close it once the
     * image is processed. Never waits — a full house is 503
     * {@code kyc_upload_busy} straight away, so no thread sits on a body (or
     * on nothing) while it queues.
     */
    public Admission admit() {
        if (!uploadSlots.tryAcquire()) {
            throw new KycUploadBusyException(retryAfterSeconds());
        }
        return new Admission(uploadSlots);
    }

    /** One upload place; closing it more than once gives it back once. */
    public static final class Admission implements AutoCloseable {
        private final Semaphore slots;
        private final AtomicBoolean held = new AtomicBoolean(true);

        private Admission(Semaphore slots) {
            this.slots = slots;
        }

        @Override
        public void close() {
            if (held.compareAndSet(true, false)) {
                slots.release();
            }
        }
    }

    public ProcessedImage process(byte[] upload, KycFileKind kind) {
        JpegStructure.Frame frame = JpegStructure.parse(upload);
        if (frame.pixels() > limits.maxPixels()) {
            throw new KycImageRejectedException(Reason.TOO_MANY_PIXELS, "the image declares " + frame.width()
                    + "x" + frame.height() + " pixels; at most " + limits.maxPixels() + " are accepted");
        }
        if (Math.min(frame.width(), frame.height()) < limits.minSide()) {
            throw new KycImageRejectedException(Reason.TOO_SMALL, "the image is " + frame.width() + "x"
                    + frame.height() + "; its shorter side must be at least " + limits.minSide() + " pixels");
        }
        int maxSide = kind.isDocument() ? limits.documentMaxSide() : limits.selfieMaxSide();

        boolean acquired;
        try {
            acquired = decodeSlots.tryAcquire(limits.decodeWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KycUploadBusyException(retryAfterSeconds());
        }
        if (!acquired) {
            throw new KycUploadBusyException(retryAfterSeconds());
        }
        try {
            BufferedImage decoded = decode(upload, frame, maxSide);
            BufferedImage scaled = scaleToRgb(decoded, maxSide);
            return new ProcessedImage(encode(scaled), scaled.getWidth(), scaled.getHeight());
        } finally {
            decodeSlots.release();
        }
    }

    private long retryAfterSeconds() {
        return Math.max(1, limits.decodeWait().toSeconds());
    }

    private static BufferedImage decode(byte[] upload, JpegStructure.Frame frame, int maxSide) {
        ImageReader reader = jpegReader();
        List<String> warnings = new ArrayList<>();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(upload))) {
            reader.setInput(in, true, true);
            reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
            if (reader.getWidth(0) != frame.width() || reader.getHeight(0) != frame.height()) {
                throw new KycImageRejectedException(Reason.CORRUPT, "the decoder disagrees with the frame header");
            }
            ImageReadParam param = reader.getDefaultReadParam();
            // Decode no smaller than the target, so the final scale still
            // has pixels to average: 3200 -> 1600 decodes at 1/2 directly.
            int step = Math.max(1, Math.max(frame.width(), frame.height()) / maxSide);
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            BufferedImage image = reader.read(0, param);
            if (!warnings.isEmpty()) {
                throw new KycImageRejectedException(Reason.CORRUPT, "the JPEG is damaged or truncated");
            }
            return image;
        } catch (IIOException ex) {
            // e.g. "Unsupported Image Type" (CMYK/YCCK) or a corrupt table.
            throw new KycImageRejectedException(Reason.CORRUPT, "the JPEG could not be decoded");
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof KycImageRejectedException rejected) {
                throw rejected;
            }
            throw new KycImageRejectedException(Reason.CORRUPT, "the JPEG could not be decoded");
        } finally {
            reader.dispose();
        }
    }

    /**
     * Draws into a fresh RGB image at most {@code maxSide} on its longer side.
     * Always a fresh image, even at the same size: that is what normalises
     * greyscale to RGB and guarantees nothing but pixels reaches the encoder.
     * Shrinks by halving first, so a large reduction does not alias the small
     * print on an ID card.
     */
    static BufferedImage scaleToRgb(BufferedImage source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        int targetWidth = width;
        int targetHeight = height;
        if (longest > maxSide) {
            double ratio = (double) maxSide / longest;
            targetWidth = Math.max(1, (int) Math.round(width * ratio));
            targetHeight = Math.max(1, (int) Math.round(height * ratio));
        }
        BufferedImage current = source;
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return draw(current, targetWidth, targetHeight);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private byte[] encode(BufferedImage image) {
        ImageWriter writer = jpegWriter();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(limits.jpegQuality());
            // No metadata object: the writer emits a bare JFIF header and the
            // pixels — nothing carried over from the upload.
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException ex) {
            throw new IllegalStateException("JPEG re-encode failed", ex);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ImageReader jpegReader() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
        if (!readers.hasNext()) {
            throw new IllegalStateException("No JPEG reader in this JRE (java.desktop module missing?)");
        }
        return readers.next();
    }

    private static ImageWriter jpegWriter() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer in this JRE (java.desktop module missing?)");
        }
        return writers.next();
    }
}
