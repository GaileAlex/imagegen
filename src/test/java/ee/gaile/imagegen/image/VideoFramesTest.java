package ee.gaile.imagegen.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoFramesTest {

    /**
     * H.264 High, yuv420p, 320x180 (the coded height is 192: the decoder must crop), 24 frames made by ffmpeg with
     * libx264 like the workflows' videos: red left and blue right for the first half second, then all green.
     */
    static Path testVideo() throws URISyntaxException {
        return Path.of(VideoFramesTest.class.getResource("/video/first-frame.mp4").toURI());
    }

    static boolean reddish(int rgb) {
        return (rgb >> 16 & 0xFF) > 200 && (rgb >> 8 & 0xFF) < 60 && (rgb & 0xFF) < 60;
    }

    static boolean bluish(int rgb) {
        return (rgb & 0xFF) > 200 && (rgb >> 16 & 0xFF) < 60 && (rgb >> 8 & 0xFF) < 60;
    }

    @Test
    void theFirstFrameIsDecodedInItsShownSize() throws Exception {
        BufferedImage frame = new VideoFrames().first(testVideo());

        assertThat(frame.getWidth()).isEqualTo(320);
        assertThat(frame.getHeight()).isEqualTo(180);
        assertThat(reddish(frame.getRGB(40, 90))).isTrue();
        assertThat(bluish(frame.getRGB(280, 90))).isTrue();
        assertThat(bluish(frame.getRGB(280, 178))).isTrue();
    }

    @Test
    void aFileThatIsNoVideoFails(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("broken.mp4"), "not a video");

        assertThatThrownBy(() -> new VideoFrames().first(file)).isInstanceOf(ThumbnailFailedException.class);
        assertThatThrownBy(() -> new VideoFrames().first(directory.resolve("missing.mp4")))
                .isInstanceOf(ThumbnailFailedException.class);
    }
}
