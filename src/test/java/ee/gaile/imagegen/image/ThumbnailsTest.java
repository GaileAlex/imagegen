package ee.gaile.imagegen.image;

import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.storage.JobFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThumbnailsTest {

    private static final UUID ID = UUID.fromString("3f0c0000-0000-0000-0000-000000000002");
    private static final JobResult PICTURE = new JobResult("image/png", 0, 0, 0, null);
    private static final JobResult VIDEO = new JobResult("video/mp4", 0, 320, 180, 1.0);

    @TempDir
    Path directory;
    private JobFiles files;
    private Thumbnails thumbnails;

    @BeforeEach
    void setUp() {
        files = new JobFiles(directory);
        thumbnails = new Thumbnails(files, new VideoFrames());
    }

    /** Left half red, right half blue, with an alpha channel like a PNG may have */
    private void writeResult(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, x < width / 2 ? 0xFFFF0000 : 0xFF0000FF);
            }
        }
        ImageIO.write(image, "png", files.result(ID, "image/png").toFile());
    }

    private static BufferedImage read(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    @Test
    void aPictureBecomesAJpegWithTheShorterSideOf480() throws IOException {
        writeResult(720, 1248);

        Path thumbnail = thumbnails.of(ID, PICTURE);

        assertThat(thumbnail).isEqualTo(directory.resolve(ID + ".thumbnail.jpg"));
        assertThat(Files.readAllBytes(thumbnail)).startsWith(0xFF, 0xD8);
        BufferedImage image = read(thumbnail);
        assertThat(image.getWidth()).isEqualTo(480);
        assertThat(image.getHeight()).isEqualTo(832);
        assertThat(VideoFramesTest.reddish(image.getRGB(60, 400))).isTrue();
        assertThat(VideoFramesTest.bluish(image.getRGB(420, 400))).isTrue();
        assertThat(Files.size(thumbnail)).isLessThan(60_000);
        try (var leftovers = Files.list(directory)) {
            assertThat(leftovers.map(file -> file.getFileName().toString()))
                    .containsExactlyInAnyOrder(ID + ".png", ID + ".thumbnail.jpg");
        }
    }

    @Test
    void aThumbnailIsMadeOnceAndGoesWithTheJob() throws IOException {
        writeResult(1024, 1024);
        Path thumbnail = thumbnails.of(ID, PICTURE);
        FileTime old = FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(thumbnail, old);

        assertThat(thumbnails.of(ID, PICTURE)).isEqualTo(thumbnail);
        assertThat(Files.getLastModifiedTime(thumbnail)).isEqualTo(old);

        files.deleteAll(ID);
        assertThat(thumbnail).doesNotExist();
    }

    @Test
    void aVideoGivesItsFirstFrameAndASmallVideoKeepsItsSize() throws Exception {
        Files.copy(VideoFramesTest.testVideo(), files.result(ID, "video/mp4"));

        BufferedImage image = read(thumbnails.of(ID, VIDEO));

        assertThat(image.getWidth()).isEqualTo(320);
        assertThat(image.getHeight()).isEqualTo(180);
        assertThat(VideoFramesTest.reddish(image.getRGB(40, 90))).isTrue();
        assertThat(VideoFramesTest.bluish(image.getRGB(280, 90))).isTrue();
    }

    @Test
    void anUnreadableResultFailsAndLeavesNoFile() throws IOException {
        Files.writeString(files.result(ID, "image/png"), "not a picture");

        assertThatThrownBy(() -> thumbnails.of(ID, PICTURE)).isInstanceOf(ThumbnailFailedException.class);
        assertThat(files.thumbnail(ID)).doesNotExist();
    }
}
