package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.model.ParamNames;
import ee.gaile.imagegen.model.workflow.WorkflowRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ee.gaile.imagegen.model.TestCatalogue.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowValuesTest {

    private static final WorkflowRepository WORKFLOWS = new WorkflowRepository(MAPPER);

    private static Map<String, Object> of(Job job) {
        return WorkflowValues.of(job, WORKFLOWS.get(job.getModel()));
    }

    private static Map<String, Object> scene(Job job, int scene) {
        return WorkflowValues.scene(job, WORKFLOWS.get(job.getModel()), scene);
    }

    private Job job(String model, JobKind kind, Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        params.put("seed", 5L);
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Job(UUID.fromString("3f0c0000-0000-0000-0000-000000000001"), 1, "u", model, kind, params, 5L, null);
    }

    @Test
    void theFilenamePrefixIsTheJobId() {
        Map<String, Object> values = of(job("muse-txt2img", JobKind.IMAGE, "prompt", "x"));

        assertThat(values).containsEntry("filenamePrefix", "3f0c0000-0000-0000-0000-000000000001")
                .containsEntry("prompt", "x").doesNotContainKeys("image", "mask");
    }

    @Test
    void theTranslationReplacesTheRussianText() {
        Job job = job("muse-txt2img", JobKind.IMAGE, "prompt", "рыжий кот");
        job.setTranslations(Map.of("prompt", "a ginger cat"));

        assertThat(of(job)).containsEntry("prompt", "a ginger cat");
    }

    @Test
    void editAndInpaintNameTheUploadedFiles() {
        Map<String, Object> edit = of(job("muse-edit", JobKind.EDIT, "width", 1168, "height", 880));
        Map<String, Object> inpaint = of(job("muse-inpaint", JobKind.INPAINT));

        assertThat(edit).containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.input.png")
                .doesNotContainKey("mask").containsEntry("width", 1168);
        assertThat(inpaint).containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.input.png")
                .containsEntry("mask", "3f0c0000-0000-0000-0000-000000000001.mask.png");
    }

    @Test
    void wanSecondsBecomeFramesAt16FpsAndTheSizeFollowsTheOrientation() {
        Map<String, Object> landscape = of(job("wan22-i2v-fastmove", JobKind.VIDEO,
                "seconds", 5, "orientation", "landscape"));
        Map<String, Object> portrait = of(job("wan22-i2v-fastmove", JobKind.VIDEO,
                "seconds", 2, "orientation", "portrait"));

        // 720p: the author of the weights, "if the face changes, increase the resolution"
        assertThat(landscape).containsEntry("frames", 81).containsEntry("width", 1280).containsEntry("height", 720)
                .doesNotContainKeys("halfWidth", "halfHeight");
        assertThat(portrait).containsEntry("frames", 33).containsEntry("width", 720).containsEntry("height", 1280);
    }

    @Test
    void aWanSceneBySviKeepsTheStartImageAsItsAnchorAndSharesFiveFramesWithTheSceneBefore() {
        Job job = job("wan22-i2v-fastmove", JobKind.VIDEO, "prompt", "the whole story", "seconds", 10,
                "orientation", "portrait", "keepFace", true);
        job.setScenes(List.of(new Scene(5, "he walks", null), new Scene(5, "he sits", null)));
        var svi = WORKFLOWS.get(WORKFLOWS.get("wan22-i2v-fastmove").sceneWorkflow());

        Map<String, Object> first = WorkflowValues.scene(job, svi, 1);
        Map<String, Object> second = WorkflowValues.scene(job, svi, 2);

        assertThat(svi.latentChain()).isNotNull();
        assertThat(first).containsEntry("frames", 81).containsEntry("width", 544).containsEntry("height", 960)
                .containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.input.png")
                .containsEntry("keepFace", false).containsEntry("seed", 5L);
        assertThat(second).containsEntry("frames", 85).containsEntry("prompt", "he sits")
                .containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.input.png")
                .containsEntry("seed", 6L);
    }

    @Test
    void ltxSecondsBecomeFramesAt24FpsAtTheFinalSize() {
        Map<String, Object> landscape = of(job("ltx25-nsfw-i2v", JobKind.VIDEO,
                "seconds", 5, "orientation", "landscape"));
        Map<String, Object> portrait = of(job("ltx25-nsfw-i2v", JobKind.VIDEO,
                "seconds", 8, "orientation", "portrait"));

        // one stage at the final size (the author of the weights), no half-size first stage
        assertThat(landscape).containsEntry("frames", 121).containsEntry("width", 1216).containsEntry("height", 704)
                .doesNotContainKeys("halfWidth", "halfHeight");
        assertThat(portrait).containsEntry("frames", 193).containsEntry("width", 704).containsEntry("height", 1216);
    }

    @Test
    void framesSatisfyTheRulesOfTheModels() {
        for (int seconds = 2; seconds <= 8; seconds++) {
            int wan = (Integer) of(job("wan22-i2v-fastmove", JobKind.VIDEO, "seconds", seconds,
                    "orientation", "landscape")).get("frames");
            int ltx = (Integer) of(job("ltx25-nsfw-i2v", JobKind.VIDEO, "seconds", seconds,
                    "orientation", "landscape")).get("frames");
            assertThat((wan - 1) % 4).isZero();
            assertThat((ltx - 1) % 8).isZero();
        }
    }

    @Test
    void ltxSizesAreMultiplesOf32() {
        for (String orientation : new String[]{"landscape", "portrait"}) {
            Map<String, Object> values = of(job("ltx25-nsfw-i2v", JobKind.VIDEO, "seconds", 5,
                    "orientation", orientation));
            assertThat((Integer) values.get("width") % 32).isZero();
            assertThat((Integer) values.get("height") % 32).isZero();
        }
    }

    @Test
    void theFirstSceneStartsFromTheStartImageAndTheNextFromTheTailOfTheOneBefore() {
        Job job = job("ltx25-nsfw-i2v", JobKind.VIDEO, "prompt", "the whole story", "seconds", 20,
                "orientation", "portrait", "keepFace", true, ParamNames.TEXT_ONLY, true);
        job.setScenes(List.of(new Scene(10, "he plays", null), new Scene(10, "он встаёт", "he stands up")));

        Map<String, Object> first = scene(job, 1);
        Map<String, Object> second = scene(job, 2);

        assertThat(first).containsEntry("prompt", "he plays").containsEntry("frames", 241)
                .containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.input.png")
                .containsEntry(ParamNames.TEXT_ONLY, true).containsEntry("keepFace", false)
                .containsEntry("seed", 5L).containsEntry("width", 704)
                .containsEntry("filenamePrefix", "3f0c0000-0000-0000-0000-000000000001-scene1");
        // the 17 frames of the tail, then 10 s more; the English text of a Russian scene
        assertThat(second).containsEntry("prompt", "he stands up").containsEntry("frames", 257)
                .containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.tail.png")
                .containsEntry(ParamNames.TEXT_ONLY, false).containsEntry("seed", 6L);
    }

    @Test
    void sceneFramesSatisfyTheRulesOfTheModels() {
        for (int seconds = 2; seconds <= 10; seconds++) {
            for (String model : new String[]{"wan22-i2v-fastmove", "ltx25-nsfw-i2v"}) {
                Job job = job(model, JobKind.VIDEO, "seconds", 2 * seconds, "orientation", "landscape");
                job.setScenes(List.of(new Scene(seconds, "a", null), new Scene(seconds, "b", null)));
                int step = model.startsWith("ltx") ? 8 : 4;
                assertThat(((Integer) scene(job, 1).get("frames") - 1) % step).isZero();
                assertThat(((Integer) scene(job, 2).get("frames") - 1) % step).isZero();
            }
        }
    }
}
