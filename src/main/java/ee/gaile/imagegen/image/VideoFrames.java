package ee.gaile.imagegen.image;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Frames of a result video (H.264 High in MP4, what the workflows save), decoded by JCodec in pure Java: the JDK has
 * no H.264 decoder, and ffmpeg would add ~600 MB to the image for one frame.
 */
@Component
public class VideoFrames {

    /**
     * The first frame: the start picture of the video for most modes. ~0.1-0.3 s for 1216x704.
     *
     * @throws ThumbnailFailedException the file cannot be read or decoded
     */
    public BufferedImage first(Path video) {
        try {
            Picture frame = FrameGrab.getFrameFromFile(video.toFile(), 0);
            if (frame == null) {
                throw new ThumbnailFailedException("The video has no frame");
            }
            return AWTUtil.toBufferedImage(frame);
        } catch (IOException | JCodecException | RuntimeException e) {
            if (e instanceof ThumbnailFailedException failed) {
                throw failed;
            }
            throw new ThumbnailFailedException("The first frame could not be decoded: " + e);
        }
    }
}
