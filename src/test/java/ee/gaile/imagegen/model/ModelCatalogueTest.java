package ee.gaile.imagegen.model;

import ee.gaile.imagegen.job.JobKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogueTest {

    private final ModelCatalogue catalogue = TestCatalogue.get();

    private ParamDefinition param(String model, String name) {
        return TestCatalogue.model(model).params().stream().filter(param -> param.name().equals(name)).findFirst()
                .orElseThrow();
    }

    @Test
    void listsTheTenModesInTheOrderOfTheContract() {
        assertThat(catalogue.list()).extracting(ModelDefinition::id).containsExactly("muse-txt2img",
                "krea2-realism-txt2img", "cyberrealistic-txt2img", "moody-txt2img",
                "muse-edit", "muse-inpaint", "wan22-i2v-fastmove",
                "wan22-i2v", "ltx25-i2v", "ltx25-nsfw-i2v");
    }

    @Test
    void imagesNeedNoInputInpaintNeedsAMaskAndLtxRunsFromTheTextAlone() {
        ModelDefinition txt = TestCatalogue.model("muse-txt2img");
        ModelDefinition inpaint = TestCatalogue.model("muse-inpaint");
        ModelDefinition video = TestCatalogue.model("ltx25-i2v");

        assertThat(txt.kind()).isEqualTo(JobKind.IMAGE);
        assertThat(txt.needsImage()).isFalse();
        assertThat(inpaint.needsImage()).isTrue();
        assertThat(inpaint.needsMask()).isTrue();
        assertThat(video.needsImage()).isFalse();
        assertThat(video.acceptsImage()).isTrue();
        assertThat(TestCatalogue.model("wan22-i2v-fastmove").needsImage()).isTrue();
        assertThat(txt.acceptsImage()).isFalse();
        assertThat(video.needsMask()).isFalse();
        assertThat(txt.outputType()).isEqualTo("image/png");
        assertThat(video.outputType()).isEqualTo("video/mp4");
    }

    @Test
    void museDefaultsComeFromTheParamsFile() {
        assertThat(param("muse-txt2img", "steps").defaultValue()).isEqualTo(16);
        assertThat(param("muse-txt2img", "cfg").defaultValue()).isEqualTo(2.0);
        assertThat(param("muse-txt2img", "width").defaultValue()).isEqualTo(1024);
        assertThat(param("muse-edit", "steps").defaultValue()).isEqualTo(10);
        assertThat(param("muse-edit", "cfg").defaultValue()).isEqualTo(1.0);
    }

    @Test
    void theFastPictureModesRunAtCfgOneWithTheirOwnSteps() {
        assertThat(param("krea2-realism-txt2img", "steps").defaultValue()).isEqualTo(6);
        assertThat(param("cyberrealistic-txt2img", "steps").defaultValue()).isEqualTo(9);
        assertThat(param("moody-txt2img", "steps").defaultValue()).isEqualTo(8);
        for (String id : List.of("krea2-realism-txt2img", "cyberrealistic-txt2img", "moody-txt2img")) {
            assertThat(TestCatalogue.model(id).kind()).isEqualTo(JobKind.IMAGE);
            assertThat(TestCatalogue.model(id).params()).extracting(ParamDefinition::name)
                    .containsExactly("prompt", "steps", "width", "height", "seed");
        }
    }

    @Test
    void rangesAreThoseOfTheTask() {
        ParamDefinition steps = param("muse-txt2img", "steps");
        assertThat(steps.min()).isEqualTo(8);
        assertThat(steps.max()).isEqualTo(40);
        assertThat(param("muse-txt2img", "cfg").max()).isEqualTo(4.0);
        ParamDefinition width = param("muse-txt2img", "width");
        assertThat(List.of(width.min(), width.max(), width.step())).containsExactly(512, 2048, 16);
        assertThat(param("wan22-i2v-fastmove", "seconds").max()).isEqualTo(20);
        assertThat(param("ltx25-i2v", "seconds").max()).isEqualTo(30);
        // longer than one clip = scenes
        assertThat(TestCatalogue.model("wan22-i2v-fastmove").clipSeconds()).isEqualTo(6);
        assertThat(TestCatalogue.model("ltx25-i2v").clipSeconds()).isEqualTo(12);
        assertThat(TestCatalogue.model("muse-txt2img").clipSeconds()).isNull();
    }

    @Test
    void videoParamsHaveAnOrientationEnumAndSeconds() {
        ParamDefinition orientation = param("wan22-i2v-fastmove", "orientation");
        assertThat(orientation.type()).isEqualTo(ParamType.ENUM);
        assertThat(orientation.options()).containsExactly("landscape", "portrait");
        assertThat(orientation.defaultValue()).isEqualTo("landscape");
        assertThat(param("ltx25-i2v", "seconds").defaultValue()).isEqualTo(5);
    }

    @Test
    void editAndInpaintTakeTheirSizeFromThePhoto() {
        for (String id : List.of("muse-edit", "muse-inpaint")) {
            assertThat(TestCatalogue.model(id).params()).extracting(ParamDefinition::name)
                    .doesNotContain("width", "height");
        }
    }

    @Test
    void theSeedHasNoDefaultAndOnlyThePromptIsRequired() {
        ParamDefinition seed = param("muse-txt2img", "seed");
        assertThat(seed.type()).isEqualTo(ParamType.SEED);
        assertThat(seed.defaultValue()).isNull();
        assertThat(param("muse-txt2img", "prompt").required()).isTrue();
        assertThat(param("muse-txt2img", "negativePrompt").required()).isNull();
    }

    @Test
    void jsonOmitsWhatDoesNotApply() {
        String json = TestCatalogue.MAPPER.writeValueAsString(param("muse-txt2img", "steps"));

        assertThat(json).contains("\"default\":16").contains("\"min\":8").doesNotContain("options")
                .doesNotContain("required");
    }
}
