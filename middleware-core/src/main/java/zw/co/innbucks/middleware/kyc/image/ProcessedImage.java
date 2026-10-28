package zw.co.innbucks.middleware.kyc.image;

/**
 * A re-encoded JPEG: pixels only, no metadata, at most the kind's longest side.
 * {@link #toString()} never prints the bytes.
 */
public record ProcessedImage(byte[] jpeg, int width, int height) {

    public int byteSize() {
        return jpeg.length;
    }

    @Override
    public String toString() {
        return "ProcessedImage[" + width + "x" + height + ", " + jpeg.length + " bytes]";
    }
}
