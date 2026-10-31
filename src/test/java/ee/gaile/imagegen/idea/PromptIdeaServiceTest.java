package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.JobQueue;
import ee.gaile.imagegen.job.JobResult;
import ee.gaile.imagegen.job.JobStatus;
import ee.gaile.imagegen.model.TestCatalogue;
import ee.gaile.imagegen.service.InvalidParamsException;
import ee.gaile.imagegen.service.SourceImages;
import ee.gaile.imagegen.storage.JobFiles;
import ee.gaile.imagegen.storage.JobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromptIdeaServiceTest {

    @TempDir
    Path directory;
    @Mock
    private JobStore store;
    @Mock
    private JobQueue queue;
    private JobFiles files;
    private FakeWriter writer;
    private PromptIdeaService service;

    @BeforeEach
    void setUp() {
        files = new JobFiles(directory);
        writer = new FakeWriter();
        service = new PromptIdeaService(TestCatalogue.get(), new SourceImages(store, files), writer, queue,
                TestCatalogue.MAPPER);
    }

    @Test
    void aFrameIdeaGivesBothPromptsFromOneAnswer() {
        PromptIdea idea = service.write(1, request("ltx25-nsfw-i2v", 8, true, "portrait", null), null);

        assertThat(idea).isEqualTo(new PromptIdea("frame text", "video text"));
        assertThat(writer.schemaFields).containsExactly("framePrompt", "videoPrompt");
        assertThat(writer.system).contains("portrait (tall)").contains("8-second clip with sound");
        assertThat(writer.user).isEqualTo("Idea: люди гуляют в парке");
        assertThat(writer.picture).isNull();
    }

    @Test
    void theGpuIsUsedWhenTheQueueLendsItAndGivenBack() {
        writer.gpu = true;
        given(queue.lendGpu()).willReturn(true);

        service.write(1, request("ltx25-nsfw-i2v", 5, true, null, null), null);

        assertThat(writer.devices).containsExactly("GPU");
        verify(queue).returnGpu();
    }

    @Test
    void theCpuTakesOverWhenAJobHasTheGpuOrTheGpuFails() {
        writer.gpu = true;
        given(queue.lendGpu()).willReturn(false);
        service.write(1, request("ltx25-nsfw-i2v", 5, true, null, null), null);
        assertThat(writer.devices).containsExactly("CPU");
        verify(queue, never()).returnGpu();

        writer.devices.clear();
        writer.gpuFails = true;
        given(queue.lendGpu()).willReturn(true);
        PromptIdea idea = service.write(1, request("ltx25-nsfw-i2v", 5, true, null, null), null);
        assertThat(idea.videoPrompt()).isEqualTo("video text");
        assertThat(writer.devices).containsExactly("GPU", "CPU");
        verify(queue).returnGpu();
    }

    @Test
    void anUploadedPictureIsLookedAtAndOnlyTheVideoPromptComesBack() throws IOException {
        PromptIdea idea = service.write(1, request("wan22-i2v-fastmove", null, false, null, null), png(2000, 1000));

        assertThat(idea.framePrompt()).isNull();
        assertThat(idea.videoPrompt()).isEqualTo("video text");
        assertThat(writer.schemaFields).containsExactly("picture", "videoPrompt");
        // the default length of the mode, the Wan style
        assertThat(writer.system).contains("5-second clip without sound");
        BufferedImage seen = ImageIO.read(new ByteArrayInputStream(writer.picture));
        assertThat(seen.getWidth()).isEqualTo(PromptIdeaService.PICTURE_SIDE);
    }

    @Test
    void withAPictureTheIdeaMayBeEmptyAndTheModelChoosesTheAction() throws IOException {
        PromptIdea idea = service.write(1, "{\"idea\":\"  \",\"videoModel\":\"wan22-i2v-fastmove\"}", png(640, 480));

        assertThat(idea.videoPrompt()).isEqualTo("video text");
        assertThat(writer.schemaFields).containsExactly("picture", "living", "action", "videoPrompt");
        assertThat(writer.user).isEqualTo("No idea: choose the action yourself from the picture.");
        // the rules of an idea are left out: their people walking in brought people into empty rooms
        assertThat(writer.system).contains("There is no idea: you choose what happens")
                .doesNotContain("the woman on the bed");
        assertThat(writer.picture).isNotNull();
    }

    @Test
    void anEmptyPlaceLosesTheSentencesOnWhatIsNotThere() throws IOException {
        writer.answers.put("living", "None.");
        writer.answers.put("videoPrompt", "The camera pans across the bed. No person enters the frame. "
                + "The light dims! Nothing else moves. There are no sounds.");

        PromptIdea idea = service.write(1, "{\"videoModel\":\"wan22-i2v-fastmove\"}", png(640, 480));

        assertThat(idea.videoPrompt()).isEqualTo("The camera pans across the bed. The light dims!");

        // with someone in the picture the prompt is left as it is
        writer.answers.put("living", "a man");
        writer.answers.put("videoPrompt", "The man laughs. Nobody else is around.");
        assertThat(service.write(1, "{\"videoModel\":\"wan22-i2v-fastmove\"}", png(640, 480)).videoPrompt())
                .isEqualTo("The man laughs. Nobody else is around.");
    }

    @Test
    void aPictureOfTheHistoryBringsItsEnglishPrompt() throws IOException {
        UUID sourceId = UUID.randomUUID();
        Job source = new Job(sourceId, 1, "a", "muse-txt2img", JobKind.IMAGE,
                Map.of("seed", 1, "prompt", "кот на диване"), 1, null);
        source.setStatus(JobStatus.DONE);
        source.setResult(new JobResult("image/png", 1, 640, 480, null));
        source.setTranslations(Map.of("prompt", "a cat on a sofa"));
        Files.write(files.result(sourceId, "image/png"), png(640, 480));
        given(store.find(sourceId)).willReturn(Optional.of(source));

        service.write(1, request("ltx25-nsfw-i2v", 5, false, null, sourceId), null);

        assertThat(writer.user).endsWith("The picture was generated from this prompt: a cat on a sofa");
        assertThat(writer.picture).isNotNull();
    }

    @Test
    void textOnlyIsForTheModesThatRunWithoutAPicture() {
        service.write(1, request("ltx25-nsfw-i2v", 12, false, null, null), null);

        assertThat(writer.schemaFields).containsExactly("videoPrompt");
        // the looks are asked for
        assertThat(writer.system).contains("12-second clip").contains("the looks and clothes of the people");

        assertThatThrownBy(() -> service.write(1, request("wan22-i2v-fastmove", 5, false, null, null), null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("start picture");
    }

    @Test
    void badRequestsAreRefusedBeforeTheModelIsAsked() {
        assertThatThrownBy(() -> service.write(1, request("muse-txt2img", 5, true, null, null), null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("Not a video model");
        // without a picture there is nothing to go on
        assertThatThrownBy(() -> service.write(1, "{\"idea\":\"  \",\"videoModel\":\"ltx25-nsfw-i2v\",\"frame\":true}",
                null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> service.write(1, "{\"videoModel\":\"ltx25-nsfw-i2v\"}", null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> service.write(1, "{\"idea\":\"" + "a".repeat(1001)
                + "\",\"videoModel\":\"ltx25-nsfw-i2v\"}", null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("longer");
        assertThatThrownBy(() -> service.write(1, "{ not json", null))
                .isInstanceOf(InvalidParamsException.class);
        assertThat(writer.system).isNull();
    }

    @Test
    void aPictureOfAnotherUserIsRefused() {
        UUID sourceId = UUID.randomUUID();
        Job source = new Job(sourceId, 2, "b", "muse-txt2img", JobKind.IMAGE, Map.of("seed", 1), 1, null);
        source.setStatus(JobStatus.DONE);
        source.setResult(new JobResult("image/png", 1, 640, 480, null));
        given(store.find(sourceId)).willReturn(Optional.of(source));

        assertThatThrownBy(() -> service.write(1, request("ltx25-nsfw-i2v", 5, false, null, sourceId), null))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("sourceJobId");
    }

    @Test
    void aVideoLongerThanOneClipGetsItsScenesWrittenOutFromOneAnswer() {
        PromptIdea idea = service.write(1, request("ltx25-nsfw-i2v", 30, true, null, null), null);

        assertThat(writer.schemaFields).containsExactly("framePrompt", "cast", "place", "sound", "story", "scene1",
                "scene1End", "scene2", "scene2End", "scene3");
        assertThat(writer.system).contains("30 seconds long")
                .contains("3 scenes (scene 1: 10 s, scene 2: 10 s, scene 3: 10 s)")
                .contains("never with their backs to the camera");
        assertThat(idea.framePrompt()).isEqualTo("frame text");
        assertThat(idea.videoPrompt()).isEqualTo("[Scene 1 · 10 s · LTX]\nscene1 text\n\n[Scene 2 · 10 s · LTX]\n"
                + "scene2 text\n\n[Scene 3 · 10 s · LTX]\nscene3 text");
    }

    @Test
    void theScenesOfWanHaveNoSound() {
        PromptIdea idea = service.write(1, request("wan22-i2v-fastmove", 13, true, null, null), null);

        assertThat(writer.schemaFields).doesNotContain("sound").contains("scene3").doesNotContain("scene3End");
        assertThat(writer.system).contains("without sound").contains("scene 1: 5 s, scene 2: 4 s, scene 3: 4 s");
        assertThat(idea.videoPrompt()).startsWith("[Scene 1 · 5 s · Wan]\n");
    }

    private static String request(String model, Integer seconds, boolean frame, String orientation, UUID source) {
        return "{\"idea\":\"люди гуляют в парке\",\"videoModel\":\"" + model + "\""
                + (seconds != null ? ",\"seconds\":" + seconds : "")
                + ",\"frame\":" + frame
                + (orientation != null ? ",\"orientation\":\"" + orientation + "\"" : "")
                + (source != null ? ",\"sourceJobId\":\"" + source + "\"" : "") + "}";
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** Answers every field of the schema with "<first word of the field> text" */
    private static final class FakeWriter implements IdeaWriter {
        String system;
        String user;
        byte[] picture;
        List<String> schemaFields;
        boolean gpu;
        boolean gpuFails;
        final List<String> devices = new ArrayList<>();
        /** answers of the fields given by the test */
        final Map<String, String> answers = new HashMap<>();

        @Override
        public boolean hasGpu() {
            return gpu;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, String> write(String system, String user, byte[] picture, Map<String, Object> schema,
                                         boolean onGpu) {
            devices.add(onGpu ? "GPU" : "CPU");
            if (onGpu && gpuFails) {
                throw new IdeaUnavailableException("down");
            }
            this.system = system;
            this.user = user;
            this.picture = picture;
            this.schemaFields = (List<String>) schema.get("required");
            Map<String, String> answer = new LinkedHashMap<>();
            schemaFields.forEach(field -> answer.put(field,
                    answers.getOrDefault(field, field.replace("Prompt", "") + " text")));
            return answer;
        }
    }
}
