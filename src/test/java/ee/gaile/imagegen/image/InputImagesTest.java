package ee.gaile.imagegen.image;

import ee.gaile.imagegen.service.InvalidParamsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputImagesTest {

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** Left half red, right half blue */
    private static BufferedImage twoColours(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, x < width / 2 ? 0xFFFF0000 : 0xFF0000FF);
            }
        }
        return image;
    }

    /** A jpeg with an EXIF block that only says "orientation = value" (big endian TIFF, one entry) */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] exif = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00};
        byte[] result = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(exif, 0, result, 2, exif.length);
        System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
        return result;
    }

    private static boolean reddish(int rgb) {
        return (rgb >> 16 & 0xFF) > 180 && (rgb & 0xFF) < 90;
    }

    private static boolean bluish(int rgb) {
        return (rgb & 0xFF) > 180 && (rgb >> 16 & 0xFF) < 90;
    }

    @Test
    void decodesPngAndJpeg() throws IOException {
        BufferedImage source = twoColours(64, 32, BufferedImage.TYPE_INT_RGB);

        BufferedImage png = InputImages.decode(encode(source, "png"));
        BufferedImage jpeg = InputImages.decode(encode(source, "jpeg"));

        assertThat(png.getWidth()).isEqualTo(64);
        assertThat(reddish(png.getRGB(4, 4))).isTrue();
        assertThat(jpeg.getHeight()).isEqualTo(32);
        assertThat(bluish(jpeg.getRGB(60, 4))).isTrue();
    }

    @Test
    void decodesWebp() {
        // A 1x1 lossless webp
        byte[] webp = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

        BufferedImage image = InputImages.decode(webp);

        assertThat(image.getWidth()).isEqualTo(1);
        assertThat(image.getHeight()).isEqualTo(1);
    }

    @Test
    void appliesTheExifOrientationOfAJpeg() throws IOException {
        byte[] jpeg = encode(twoColours(64, 32, BufferedImage.TYPE_INT_RGB), "jpeg");

        // 6 = the picture must be turned 90 degrees clockwise: the left half ends up on top
        BufferedImage turned = InputImages.decode(withOrientation(jpeg, 6));

        assertThat(turned.getWidth()).isEqualTo(32);
        assertThat(turned.getHeight()).isEqualTo(64);
        assertThat(reddish(turned.getRGB(16, 5))).isTrue();
        assertThat(bluish(turned.getRGB(16, 58))).isTrue();
    }

    @Test
    void aJpegWithoutExifIsLeftAlone() throws IOException {
        BufferedImage image = InputImages.decode(encode(twoColours(64, 32, BufferedImage.TYPE_INT_RGB), "jpeg"));

        assertThat(image.getWidth()).isEqualTo(64);
    }

    @Test
    void uprightMapsEveryOrientationToTheRightPixel() {
        // 2 wide, 3 high: pixel values are their own coordinates, x + 10 * y
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2; x++) {
                image.setRGB(x, y, x + 10 * y);
            }
        }
        // orientation -> {width, height, value at (0,0), value at (last x, last y)}
        int[][] expected = {
                {2, 3, 0, 21},   // 1 as it is is handled separately; row kept for the index
                {2, 3, 1, 20},   // 2 mirror horizontally
                {2, 3, 21, 0},   // 3 rotate 180
                {2, 3, 20, 1},   // 4 mirror vertically
                {3, 2, 0, 21},   // 5 transpose
                {3, 2, 20, 1},   // 6 rotate clockwise
                {3, 2, 21, 0},   // 7 transverse
                {3, 2, 1, 20}    // 8 rotate counter-clockwise
        };
        for (int orientation = 2; orientation <= 8; orientation++) {
            BufferedImage result = InputImages.upright(image, orientation);
            int[] want = expected[orientation - 1];
            assertThat(result.getWidth()).as("width of " + orientation).isEqualTo(want[0]);
            assertThat(result.getHeight()).as("height of " + orientation).isEqualTo(want[1]);
            assertThat(result.getRGB(0, 0) & 0xFFFFFF).as("first pixel of " + orientation).isEqualTo(want[2]);
            assertThat(result.getRGB(want[0] - 1, want[1] - 1) & 0xFFFFFF).as("last pixel of " + orientation)
                    .isEqualTo(want[3]);
        }
        assertThat(InputImages.upright(image, 1)).isSameAs(image);
    }

    @Test
    void aTransparentPngIsPlacedOnWhite() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

        BufferedImage decoded = InputImages.decode(encode(image, "png"));

        assertThat(decoded.getRGB(1, 1) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    void refusesWhatIsNotAnImage() {
        assertThatThrownBy(() -> InputImages.decode("hello".getBytes())).isInstanceOf(InvalidParamsException.class)
                .hasMessageContaining("unreadable");
        assertThatThrownBy(() -> InputImages.decode(new byte[0])).isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void refusesOtherImageFormats() throws IOException {
        assertThatThrownBy(() -> InputImages.decode(encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB),
                "gif"))).isInstanceOf(InvalidParamsException.class).hasMessageContaining("jpeg, png or webp");
    }

    @Test
    void editSizeIsAtMostAboutOneMegapixelInMultiplesOf16() {
        Dimension landscape = InputImages.editSize(4000, 3000);
        Dimension portrait = InputImages.editSize(3000, 4000);
        Dimension square = InputImages.editSize(3000, 3000);

        assertThat(landscape).isEqualTo(new Dimension(1168, 880));
        assertThat(portrait).isEqualTo(new Dimension(880, 1168));
        assertThat(square).isEqualTo(new Dimension(1024, 1024));
        for (Dimension size : new Dimension[]{landscape, portrait, square}) {
            assertThat(size.width % 16).isZero();
            assertThat(size.height % 16).isZero();
            assertThat((long) size.width * size.height).isLessThanOrEqualTo(InputImages.MAX_EDIT_PIXELS);
        }
    }

    @Test
    void aSmallPhotoIsNotEnlarged() {
        assertThat(InputImages.editSize(800, 600)).isEqualTo(new Dimension(800, 592));
        assertThat(InputImages.editSize(10, 10)).isEqualTo(new Dimension(64, 64));
    }

    @Test
    void scaleReachesTheExactSizeAfterSeveralHalvings() {
        BufferedImage big = twoColours(1000, 700, BufferedImage.TYPE_INT_RGB);

        BufferedImage small = InputImages.scale(big, 120, 80);

        assertThat(small.getWidth()).isEqualTo(120);
        assertThat(small.getHeight()).isEqualTo(80);
        assertThat(reddish(small.getRGB(10, 40))).isTrue();
        assertThat(bluish(small.getRGB(110, 40))).isTrue();
        assertThat(InputImages.scale(big, 1000, 700)).isSameAs(big);
    }

    @Test
    void limitSideShrinksOnlyLargerImages() {
        BufferedImage wide = new BufferedImage(4000, 2000, BufferedImage.TYPE_INT_RGB);

        BufferedImage limited = InputImages.limitSide(wide, 2048);

        assertThat(limited.getWidth()).isEqualTo(2048);
        assertThat(limited.getHeight()).isEqualTo(1024);
        assertThat(InputImages.limitSide(limited, 2048)).isSameAs(limited);
    }

    @Test
    void maskIsResizedToThePhotoAndTransparencyIsBlack() throws IOException {
        BufferedImage painted = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        for (int y = 10; y < 40; y++) {
            for (int x = 10; x < 40; x++) {
                painted.setRGB(x, y, 0xFFFFFFFF);
            }
        }

        BufferedImage mask = InputImages.mask(encode(painted, "png"), 200, 100);

        assertThat(mask.getWidth()).isEqualTo(200);
        assertThat(mask.getHeight()).isEqualTo(100);
        assertThat(new Color(mask.getRGB(100, 50)).getRed()).isGreaterThan(240);
        assertThat(new Color(mask.getRGB(2, 2)).getRed()).isLessThan(15);
        assertThat(mask.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void writesAReadablePng(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("a.png");

        InputImages.writePng(twoColours(8, 8, BufferedImage.TYPE_INT_RGB), file);

        assertThat(ImageIO.read(file.toFile()).getWidth()).isEqualTo(8);
    }
}
