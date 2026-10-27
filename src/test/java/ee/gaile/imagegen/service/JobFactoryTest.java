package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.MutableClock;
import ee.gaile.imagegen.model.TestCatalogue;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobFactoryTest {

    private final JobFactory factory = new JobFactory(new MutableClock(), () -> 424242L);

    private Job create(String model, Map<String, Object> params) {
        return factory.create(UUID.randomUUID(), 7, "alex", TestCatalogue.model(model), params,
                new Dimension(1168, 880));
    }

    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    @Test
    void fillsTheDefaultsAndARandomSeed() {
        Job job = create("muse-txt2img", params("prompt", "a cat"));

        assertThat(job.getParams()).containsEntry("prompt", "a cat").containsEntry("steps", 16)
                .containsEntry("cfg", 2.0).containsEntry("width", 1024).containsEntry("height", 1024)
                .containsEntry("negativePrompt", "").containsEntry("seed", 424242L);
        assertThat(job.getSeed()).isEqualTo(424242L);
        assertThat(job.getUserId()).isEqualTo(7);
        assertThat(job.getKind()).isEqualTo(JobKind.IMAGE);
    }

    @Test
    void anEmptySeedIsRandomAndAGivenSeedIsKept() {
        assertThat(create("muse-txt2img", params("prompt", "x", "seed", "")).getSeed()).isEqualTo(424242L);
        assertThat(create("muse-txt2img", params("prompt", "x", "seed", 123456789)).getSeed()).isEqualTo(123456789L);
        assertThat(create("muse-txt2img", params("prompt", "x", "seed", 0)).getSeed()).isZero();
        assertThat(create("muse-txt2img", params("prompt", "x", "seed", 2147483647L)).getSeed())
                .isEqualTo(2147483647L);
    }

    @Test
    void theRandomSeedStaysInRange() {
        JobFactory real = new JobFactory(new MutableClock());
        for (int i = 0; i < 200; i++) {
            long seed = real.create(UUID.randomUUID(), 1, null, TestCatalogue.model("muse-txt2img"),
                    params("prompt", "x"), null).getSeed();
            assertThat(seed).isBetween(0L, 2147483647L);
        }
    }

    @Test
    void rejectsASeedOutOfRange() {
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "seed", -1)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "seed", 2147483648L)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "seed", 1.5)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "seed", "abc")))
                .isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void theMissingPromptIsRefused() {
        assertThatThrownBy(() -> create("muse-txt2img", params()))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("prompt");
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "  ")))
                .isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void anUnknownParamIsRefused() {
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "sampler", "euler")))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("sampler");
        // width is derived from the photo for an edit, not a client param
        assertThatThrownBy(() -> create("muse-edit", params("prompt", "x", "width", 512)))
                .isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void valuesOutOfRangeAreRefused() {
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "steps", 7)))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("steps");
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "steps", 41)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "cfg", 4.5)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "cfg", 0.5)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("wan22-i2v-fastmove", params("prompt", "x", "seconds", 7)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("ltx25-i2v", params("prompt", "x", "seconds", 13)))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("ltx25-i2v", params("prompt", "x", "seconds", 1)))
                .isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void ltxWithoutAnImageIsMarkedTextOnly() {
        Job textOnly = factory.create(UUID.randomUUID(), 7, "alex", TestCatalogue.model("ltx25-i2v"),
                params("prompt", "x"), null, false);
        Job fromImage = create("ltx25-i2v", params("prompt", "x"));

        assertThat(textOnly.getParams()).containsEntry(JobFactory.TEXT_ONLY, true);
        assertThat(fromImage.getParams()).doesNotContainKey(JobFactory.TEXT_ONLY);
    }

    @Test
    void videosKeepTheFaceUnlessTheClientTurnsItOff() {
        assertThat(create("wan22-i2v", params("prompt", "x")).getParams()).containsEntry("keepFace", true);
        assertThat(create("ltx25-i2v", params("prompt", "x", "keepFace", false)).getParams())
                .containsEntry("keepFace", false);
        assertThatThrownBy(() -> create("ltx25-i2v", params("prompt", "x", "keepFace", "yes")))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("keepFace");
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "keepFace", true)))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("Unknown param");
    }

    @Test
    void wanStillNeedsAnImage() {
        assertThatThrownBy(() -> factory.create(UUID.randomUUID(), 7, "alex",
                TestCatalogue.model("wan22-i2v-fastmove"), params("prompt", "x"), null, false))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("needs an image");
    }

    @Test
    void theClientCannotSetTextOnly() {
        assertThatThrownBy(() -> create("ltx25-i2v", params("prompt", "x", JobFactory.TEXT_ONLY, true)))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("Unknown param");
    }

    @Test
    void boundariesAreAccepted() {
        Job job = create("muse-txt2img", params("prompt", "x", "steps", 8, "cfg", 4.0, "width", 512, "height", 2048));

        assertThat(job.getParams()).containsEntry("steps", 8).containsEntry("cfg", 4.0)
                .containsEntry("width", 512).containsEntry("height", 2048);
        assertThat(create("ltx25-i2v", params("prompt", "x", "seconds", 12)).getParams()).containsEntry("seconds", 12);
    }

    @Test
    void imageSizesMustBeAMultipleOf16() {
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "width", 1000)))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("multiple of 16");
        assertThat(create("muse-txt2img", params("prompt", "x", "width", 1008)).getParams())
                .containsEntry("width", 1008);
    }

    @Test
    void numbersMustBeNumbersAndIntegersWhole() {
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "steps", "16")))
                .isInstanceOf(InvalidParamsException.class);
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x", "steps", 16.5)))
                .isInstanceOf(InvalidParamsException.class);
        // JSON clients may send 16.0 for 16
        assertThat(create("muse-txt2img", params("prompt", "x", "steps", 16.0)).getParams())
                .containsEntry("steps", 16);
    }

    @Test
    void theEnumTakesOnlyItsOptions() {
        assertThatThrownBy(() -> create("ltx25-i2v", params("prompt", "x", "orientation", "square")))
                .isInstanceOf(InvalidParamsException.class);
        assertThat(create("ltx25-i2v", params("prompt", "x", "orientation", "portrait")).getParams())
                .containsEntry("orientation", "portrait");
    }

    @Test
    void anEmptyNegativePromptTakesTheDefaultOfTheModel() {
        Job wan = create("wan22-i2v-fastmove", params("prompt", "x", "negativePrompt", ""));

        assertThat((String) wan.getParams().get("negativePrompt")).isNotBlank();
    }

    @Test
    void aTooLongTextIsRefused() {
        assertThatThrownBy(() -> create("muse-txt2img", params("prompt", "x".repeat(4001))))
                .isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void editAndInpaintTakeTheSizeOfThePhoto() {
        Job edit = create("muse-edit", params("prompt", "add a cat"));
        Job inpaint = create("muse-inpaint", params("prompt", "a cat"));

        assertThat(edit.getParams()).containsEntry("width", 1168).containsEntry("height", 880)
                .containsEntry("steps", 10).containsEntry("cfg", 1.0);
        assertThat(inpaint.getParams()).containsEntry("width", 1168).containsEntry("height", 880)
                .containsEntry("steps", 16).containsEntry("cfg", 2.5);
    }

    @Test
    void anEditWithoutAPhotoSizeIsRefused() {
        assertThatThrownBy(() -> factory.create(UUID.randomUUID(), 1, "a", TestCatalogue.model("muse-edit"),
                params("prompt", "x"), null)).isInstanceOf(InvalidParamsException.class);
    }

    @Test
    void aVideoDoesNotGetTheSizeOfAPhoto() {
        Job video = create("wan22-i2v-fastmove", params("prompt", "x"));

        assertThat(video.getParams()).doesNotContainKeys("width", "height");
    }
}
