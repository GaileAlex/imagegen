package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobFailedException;
import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.model.TestCatalogue;
import ee.gaile.imagegen.service.JobFactory;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.storage.JobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class JobStoryboardsTest {

    @TempDir
    Path directory;
    private JobFiles files;
    private final Writer writer = new Writer();
    private JobStoryboards storyboards;

    @BeforeEach
    void setUp() {
        files = new JobFiles(directory);
        storyboards = new JobStoryboards(TestCatalogue.get(), writer, files, mock(JobStore.class));
    }

    private Job longVideo(boolean withPicture) throws IOException {
        Job job = new JobFactory(Clock.systemUTC()).create(UUID.randomUUID(), 1, "u",
                TestCatalogue.model("ltx25-i2v"), new HashMap<>(Map.of("prompt", "он играет и машет",
                        "seconds", 20)), null, withPicture);
        if (withPicture) {
            ImageIO.write(new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB), "png", files.input(job.getId()).toFile());
        }
        return job;
    }

    @Test
    void theScenesComeFromOneAnswerAboutTheStartImage() throws IOException {
        Job job = longVideo(true);

        List<Scene> scenes = storyboards.write(job);

        assertThat(scenes).containsExactly(new Scene(10, "scene1 text", null), new Scene(10, "scene2 text", null));
        assertThat(writer.devices).containsExactly("GPU");
        assertThat(writer.fields).startsWith("picture", "cast", "place", "sound", "story");
        assertThat(writer.picture).isNotNull();
        assertThat(writer.user).isEqualTo("Idea: он играет и машет");
        assertThat(writer.system).contains("20 seconds long").doesNotContain("framePrompt");
    }

    @Test
    void aVideoFromTheTextAloneHasNoPictureToLookAt() throws IOException {
        Job job = longVideo(false);

        storyboards.write(job);

        assertThat(writer.picture).isNull();
        assertThat(writer.fields).startsWith("cast").doesNotContain("picture");
    }

    @Test
    void theGpuIsTriedTwiceThenTheCpu() throws IOException {
        writer.gpuFailures = 2;

        assertThat(storyboards.write(longVideo(true))).hasSize(2);
        assertThat(writer.devices).containsExactly("GPU", "GPU", "CPU");
    }

    @Test
    void noAnswerAnywhereFailsTheStep() throws IOException {
        writer.gpuFailures = 2;
        writer.cpuFails = true;
        Job job = longVideo(true);

        assertThatThrownBy(() -> storyboards.write(job)).isInstanceOf(JobFailedException.class)
                .hasMessageStartingWith("the storyboard could not be written");
    }

    /** Answers every field with "<field> text" */
    private static final class Writer implements IdeaWriter {
        final List<String> devices = new ArrayList<>();
        int gpuFailures;
        boolean cpuFails;
        String system;
        String user;
        byte[] picture;
        List<String> fields;

        @Override
        public boolean hasGpu() {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, String> write(String system, String user, byte[] picture, Map<String, Object> schema,
                                         boolean onGpu) {
            devices.add(onGpu ? "GPU" : "CPU");
            if (onGpu && gpuFailures > 0) {
                gpuFailures--;
                throw new IdeaUnavailableException("broke off");
            }
            if (!onGpu && cpuFails) {
                throw new IdeaUnavailableException("down");
            }
            this.system = system;
            this.user = user;
            this.picture = picture;
            this.fields = (List<String>) schema.get("required");
            Map<String, String> answer = new LinkedHashMap<>();
            fields.forEach(field -> answer.put(field, field + " text"));
            return answer;
        }
    }
}
