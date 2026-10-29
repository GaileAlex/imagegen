package ee.gaile.imagegen.image;

import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.storage.JobFiles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * The small pictures of the history cards: a result is up to 2048 px and a video up to ~15 MB, a card is a square of
 * ~250 px. Made on the first request and kept next to the result as &lt;jobId&gt;.thumbnail.jpg, so it goes away with
 * the other files of the job.
 */
@Component
@RequiredArgsConstructor
public class Thumbnails {

    /** The shorter side: the cards show a square cut of the picture, at most ~240 px wide, twice that for a dense screen */
    public static final int SIDE = 480;
    private static final float QUALITY = 0.8f;

    private final JobFiles files;
    private final VideoFrames frames;

    /**
     * The thumbnail of a finished job, made now if it is not there yet. One at a time: the first visit of a long
     * history asks for dozens at once, and each video starts an ffmpeg.
     *
     * @throws ThumbnailFailedException the result could not be read
     */
    public Path of(UUID id, JobResult result) {
        Path thumbnail = files.thumbnail(id);
        if (Files.isRegularFile(thumbnail)) {
            return thumbnail;
        }
        synchronized (this) {
            if (!Files.isRegularFile(thumbnail)) {
                make(files.result(id, result.contentType()), result.contentType().startsWith("video/"), thumbnail);
            }
        }
        return thumbnail;
    }

    private void make(Path source, boolean video, Path thumbnail) {
        BufferedImage picture = video ? frames.first(source) : read(source);
        Path temporary = thumbnail.resolveSibling(thumbnail.getFileName() + ".tmp");
        try {
            writeJpeg(scaled(picture), temporary);
            Files.move(temporary, thumbnail, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // the job was deleted meanwhile: its files are gone, this one must not stay behind
            if (!Files.exists(source)) {
                Files.deleteIfExists(thumbnail);
            }
        } catch (IOException e) {
            throw new ThumbnailFailedException("The thumbnail could not be written: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                // the next attempt writes it over
            }
        }
    }

    private static BufferedImage read(Path source) {
        try {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) {
                throw new ThumbnailFailedException("The result is not a readable picture");
            }
            return image;
        } catch (IOException e) {
            throw new ThumbnailFailedException("The result could not be read: " + e.getMessage());
        }
    }

    /** The shorter side down to {@link #SIDE}, a smaller picture keeps its size; opaque, a JPEG has no alpha */
    static BufferedImage scaled(BufferedImage picture) {
        BufferedImage opaque = picture.getColorModel().hasAlpha() ? onWhite(picture) : picture;
        double scale = Math.min(1.0, (double) SIDE / Math.min(opaque.getWidth(), opaque.getHeight()));
        return InputImages.scale(opaque, Math.max(1, (int) Math.round(opaque.getWidth() * scale)),
                Math.max(1, (int) Math.round(opaque.getHeight() * scale)));
    }

    private static BufferedImage onWhite(BufferedImage picture) {
        BufferedImage rgb = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(picture, 0, 0, Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static void writeJpeg(BufferedImage picture, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(picture, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
