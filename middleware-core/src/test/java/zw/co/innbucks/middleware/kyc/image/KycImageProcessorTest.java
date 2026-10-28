package zw.co.innbucks.middleware.kyc.image;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.kyc.KycFileKind;
import zw.co.innbucks.middleware.kyc.KycProperties;
import zw.co.innbucks.middleware.kyc.image.KycImageRejectedException.Reason;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upload pipeline: a JPEG by its bytes, caps before decode, nothing of the
 * original but its pixels in what is kept.
 */
class KycImageProcessorTest {

    private static final KycProperties.Image LIMITS = new KycProperties.Image(
            2 * 1024 * 1024, 16_000_000L, 200, 1600, 1080, 0.9f, 2, Duration.ofSeconds(1));

    private final KycImageProcessor processor = new KycImageProcessor(LIMITS);

    @Test
    void aGoodJpegIsReEncodedAtItsOwnSize() {
        ProcessedImage out = processor.process(TestImages.jpeg(1200, 800), KycFileKind.ID_FRONT);

        assertThat(out.width()).isEqualTo(1200);
        assertThat(out.height()).isEqualTo(800);
        assertThat(out.jpeg()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(out.jpeg()[1] & 0xFF).isEqualTo(0xD8);
        assertThat(TestImages.decode(out.jpeg()).getWidth()).isEqualTo(1200);
        assertThat(out.byteSize()).isEqualTo(out.jpeg().length);
        assertThat(out.toString()).doesNotContain("[B@");
    }

    @Test
    void aPngIsRefusedByItsBytes() {
        assertThatThrownBy(() -> processor.process(TestImages.png(400, 300), KycFileKind.SELFIE))
                .isInstanceOfSatisfying(KycImageRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.NOT_JPEG));
    }

    @Test
    void randomBytesAndAnEmptyBodyAreNotJpegs() {
        assertThatThrownBy(() -> processor.process("hello".getBytes(StandardCharsets.UTF_8), KycFileKind.SELFIE))
                .isInstanceOfSatisfying(KycImageRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.NOT_JPEG));
        assertThatThrownBy(() -> processor.process(new byte[0], KycFileKind.SELFIE))
                .isInstanceOfSatisfying(KycImageRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.NOT_JPEG));
    }

    @Test
    void aTruncatedJpegIsRefused_notDecodedHalfGrey() {
        byte[] whole = TestImages.jpeg(800, 600);
        byte[] cut = Arrays.copyOf(whole, whole.length * 6 / 10);

        assertThatThrownBy(() -> processor.process(cut, KycFileKind.ID_BACK))
                .isInstanceOfSatisfying(KycImageRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.CORRUPT));
    }

    @Test
    void hugeDeclaredDimensionsAreRefusedBeforeAnyDecode() {
        // A few KB declaring 30000 x 30000: decoding it would need ~2.7 GB.
        byte[] bomb = TestImages.withDeclaredSize(TestImages.jpeg(300, 300), 30000, 30000);
        // A processor with NO decode slots: reaching the decoder would answer
        // "busy", so TOO_MANY_PIXELS proves the refusal came first.
        KycImageProcessor noSlots = new KycImageProcessor(LIMITS, new Semaphore(0));

        assertThatThrownBy(() -> noSlots.process(bomb, KycFileKind.ID_FRONT))
                .isInstanceOfSatisfying(KycImageRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.TOO_MANY_PIXELS));
    }

    @Test
    void aProgressiveJpegIsRefused() {
        byte[] progressive = TestImages.jpeg(TestImages.picture(600, 400, BufferedImage.TYPE_INT_RGB), true);

        assertThatThrownBy(() -> processor.process(progressive, KycFileKind.ID_FRONT))
                .isInstanceOfSatisfying(KycImageRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.UNSUPPORTED_ENCODING));
    }

    @Test
    void anImageTooSmallToReadIsRefused() {
        assertThatThrownBy(() -> processor.process(TestImages.jpeg(640, 150), KycFileKind.ID_FRONT))
                .isInstanceOfSatisfying(KycImageRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.TOO_SMALL));
    }

    @Test
    void exifAndAnythingAfterTheImageAreGone() {
        byte[] upload = TestImages.concat(
                TestImages.withExif(TestImages.jpeg(800, 600), "GPS-LAT-SECRET device-serial-SECRET"),
                "TRAILING-PAYLOAD hidden after the image".getBytes(StandardCharsets.US_ASCII));
        assertThat(TestImages.contains(upload, "Exif")).isTrue();

        ProcessedImage out = processor.process(upload, KycFileKind.ID_FRONT);

        assertThat(TestImages.contains(out.jpeg(), "Exif")).isFalse();
        assertThat(TestImages.contains(out.jpeg(), "SECRET")).isFalse();
        assertThat(TestImages.contains(out.jpeg(), "TRAILING-PAYLOAD")).isFalse();
        // and no APP1 segment at all
        for (int i = 0; i < out.jpeg().length - 1; i++) {
            if ((out.jpeg()[i] & 0xFF) == 0xFF && (out.jpeg()[i + 1] & 0xFF) == 0xE1) {
                throw new AssertionError("APP1 marker survived at " + i);
            }
        }
    }

    @Test
    void anOversizeDocumentIsScaledTo1600() {
        ProcessedImage out = processor.process(TestImages.jpeg(3200, 2000), KycFileKind.PROOF_OF_ADDRESS);

        assertThat(out.width()).isEqualTo(1600);
        assertThat(out.height()).isEqualTo(1000);
        BufferedImage decoded = TestImages.decode(out.jpeg());
        assertThat(decoded.getWidth()).isEqualTo(1600);
        assertThat(decoded.getHeight()).isEqualTo(1000);
    }

    @Test
    void anOversizeSelfieIsScaledTo1080_portraitToo() {
        ProcessedImage out = processor.process(TestImages.jpeg(1500, 2000), KycFileKind.SELFIE);

        assertThat(out.width()).isEqualTo(810);
        assertThat(out.height()).isEqualTo(1080);
    }

    @Test
    void greyscaleIsAcceptedAndStoredAsColour() {
        byte[] grey = TestImages.jpeg(TestImages.picture(600, 400, BufferedImage.TYPE_BYTE_GRAY), false);

        ProcessedImage out = processor.process(grey, KycFileKind.ID_BACK);

        assertThat(TestImages.decode(out.jpeg()).getColorModel().getNumComponents()).isEqualTo(3);
    }

    @Test
    void whenEveryDecodeSlotStaysBusyTheUploadIsToldToRetry() {
        KycImageProcessor busy = new KycImageProcessor(new KycProperties.Image(
                2 * 1024 * 1024, 16_000_000L, 200, 1600, 1080, 0.9f, 1, Duration.ofMillis(50)), new Semaphore(0));

        assertThatThrownBy(() -> busy.process(TestImages.jpeg(800, 600), KycFileKind.ID_FRONT))
                .isInstanceOfSatisfying(KycUploadBusyException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isPositive());
    }

    @Test
    void aSlotIsGivenBackEvenWhenTheDecodeFails() {
        Semaphore slots = new Semaphore(1);
        KycImageProcessor oneSlot = new KycImageProcessor(LIMITS, slots);
        byte[] damaged = TestImages.jpeg(800, 600);
        // keep the structure, corrupt the scan data
        for (int i = damaged.length / 2; i < damaged.length / 2 + 200; i++) {
            damaged[i] = (byte) 0x7F;
        }

        try {
            oneSlot.process(damaged, KycFileKind.ID_FRONT);
        } catch (KycImageRejectedException expected) {
            // either outcome is fine; the slot is what matters
        }
        assertThat(slots.availablePermits()).isEqualTo(1);
    }
}
