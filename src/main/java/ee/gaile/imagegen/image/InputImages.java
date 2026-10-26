package ee.gaile.imagegen.image;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import ee.gaile.imagegen.service.InvalidParamsException;
import lombok.experimental.UtilityClass;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;

/**
 * Photos, start frames and masks as the workflows get them: upright, opaque, PNG, at the size of the job.
 */
@UtilityClass
public class InputImages {

    /** The size of an edited photo is scaled to this many pixels at most (~1.05 MP, what Muse is comfortable with) */
    public static final long MAX_EDIT_PIXELS = 1_050_000;
    /** The longest side of a start frame of a video: the workflow crops it to 832x480 or 1216x704 anyway */
    public static final int MAX_VIDEO_SIDE = 2048;
    private static final int SIDE_MULTIPLE = 16;
    private static final int MIN_SIDE = 64;
    /** A photo of 100 MP would take ~400 MB decoded: refused before it is decoded */
    private static final long MAX_DECODED_PIXELS = 100_000_000L;
    private static final Set<String> FORMATS = Set.of("jpeg", "png", "webp");

    /**
     * @param data jpeg, png or webp
     * @return the picture turned upright by its EXIF orientation, on white where it was transparent
     * @throws InvalidParamsException not an image of these formats, or too large
     */
    public static BufferedImage decode(byte[] data) {
        return decode(data, Color.WHITE);
    }

    private static BufferedImage decode(byte[] data, Color background) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidParamsException("The image is unreadable: jpeg, png or webp expected");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase();
                if (!FORMATS.contains(format)) {
                    throw new InvalidParamsException("The image is " + format + ": jpeg, png or webp expected");
                }
                reader.setInput(input, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_DECODED_PIXELS) {
                    throw new InvalidParamsException("The image is larger than 100 megapixels");
                }
                BufferedImage image = reader.read(0);
                int orientation = format.equals("jpeg") ? exifOrientation(data) : 1;
                return upright(opaque(image, background), orientation);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidParamsException invalid) {
                throw invalid;
            }
            throw new InvalidParamsException("The image is unreadable: " + e.getMessage());
        }
    }

    /**
     * The mask as a workflow reads it: an RGB picture of the size of the photo, white = redraw. Transparent pixels
     * count as black, so a mask painted on a transparent canvas works as well.
     */
    public static BufferedImage mask(byte[] data, int width, int height) {
        return scale(decode(data, Color.BLACK), width, height);
    }

    /**
     * Scales to at most {@link #MAX_EDIT_PIXELS}, both sides rounded down to a multiple of 16 (the model works on
     * 16-pixel blocks); a smaller photo keeps its size.
     */
    public static Dimension editSize(int width, int height) {
        double scale = Math.min(1.0, Math.sqrt((double) MAX_EDIT_PIXELS / ((double) width * height)));
        return new Dimension(roundDown(width * scale), roundDown(height * scale));
    }

    /** Scales down so that the longest side is at most {@link #MAX_VIDEO_SIDE}; smaller images are kept */
    public static BufferedImage limitSide(BufferedImage image, int maxSide) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= maxSide) {
            return image;
        }
        double scale = (double) maxSide / longest;
        return scale(image, (int) Math.round(image.getWidth() * scale), (int) Math.round(image.getHeight() * scale));
    }

    /**
     * Scales in halves while the target is less than half of the current size, then once smoothly: one big step
     * with bilinear filtering leaves aliasing in a 12 MP photo reduced to 1 MP.
     */
    public static BufferedImage scale(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height) {
            return image;
        }
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = resample(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return resample(current, width, height);
    }

    public static void writePng(BufferedImage image, Path file) {
        try {
            if (!ImageIO.write(image, "png", file.toFile())) {
                throw new IllegalStateException("No PNG writer");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /**
     * @param orientation EXIF orientation 1-8, anything else is left as it is
     */
    public static BufferedImage upright(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        boolean turned = orientation >= 5;
        int[] source = image.getRGB(0, 0, width, height, null, 0, width);
        int newWidth = turned ? height : width;
        int[] target = new int[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int newX;
                int newY;
                switch (orientation) {
                    case 2 -> { newX = width - 1 - x; newY = y; }
                    case 3 -> { newX = width - 1 - x; newY = height - 1 - y; }
                    case 4 -> { newX = x; newY = height - 1 - y; }
                    case 5 -> { newX = y; newY = x; }
                    case 6 -> { newX = height - 1 - y; newY = x; }
                    case 7 -> { newX = height - 1 - y; newY = width - 1 - x; }
                    default -> { newX = y; newY = width - 1 - x; }
                }
                target[newY * newWidth + newX] = source[y * width + x];
            }
        }
        BufferedImage result = new BufferedImage(newWidth, turned ? width : height, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, result.getWidth(), result.getHeight(), target, 0, newWidth);
        return result;
    }

    static int exifOrientation(byte[] jpeg) {
        try {
            Metadata metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            // A damaged EXIF block is no reason to refuse the photo: it is used as it is
        }
        return 1;
    }

    private static BufferedImage opaque(BufferedImage image, Color background) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        return flatten(image, background);
    }

    private static BufferedImage flatten(BufferedImage image, Color background) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(background);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static BufferedImage resample(BufferedImage image, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static int roundDown(double side) {
        return Math.max(MIN_SIDE, (int) side / SIDE_MULTIPLE * SIDE_MULTIPLE);
    }
}
