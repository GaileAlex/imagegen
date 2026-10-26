package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Job;
import ee.gaile.imagegen.job.JobKind;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowValuesTest {

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
        Map<String, Object> values = WorkflowValues.of(job("muse-txt2img", JobKind.IMAGE, "prompt", "x"));

        assertThat(values).containsEntry("filenamePrefix", "3f0c0000-0000-0000-0000-000000000001")
                .containsEntry("prompt", "x").doesNotContainKeys("image", "mask");
    }

    @Test
    void editAndInpaintNameTheUploadedFiles() {
        Map<String, Object> edit = WorkflowValues.of(job("muse-edit", JobKind.EDIT, "width", 1168, "height", 880));
        Map<String, Object> inpaint = WorkflowValues.of(job("muse-inpaint", JobKind.INPAINT));

        assertThat(edit).containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.input.png")
                .doesNotContainKey("mask").containsEntry("width", 1168);
        assertThat(inpaint).containsEntry("image", "3f0c0000-0000-0000-0000-000000000001.input.png")
                .containsEntry("mask", "3f0c0000-0000-0000-0000-000000000001.mask.png");
    }

    @Test
    void wanSecondsBecomeFramesAt16FpsAndTheSizeFollowsTheOrientation() {
        Map<String, Object> landscape = WorkflowValues.of(job("wan22-i2v-fastmove", JobKind.VIDEO,
                "seconds", 5, "orientation", "landscape"));
        Map<String, Object> portrait = WorkflowValues.of(job("wan22-i2v-fastmove", JobKind.VIDEO,
                "seconds", 2, "orientation", "portrait"));

        assertThat(landscape).containsEntry("frames", 81).containsEntry("width", 832).containsEntry("height", 480)
                .doesNotContainKeys("halfWidth", "halfHeight");
        assertThat(portrait).containsEntry("frames", 33).containsEntry("width", 480).containsEntry("height", 832);
    }

    @Test
    void ltxSecondsBecomeFramesAt24FpsWithHalfSizeFirstStage() {
        Map<String, Object> landscape = WorkflowValues.of(job("ltx25-i2v", JobKind.VIDEO,
                "seconds", 5, "orientation", "landscape"));
        Map<String, Object> portrait = WorkflowValues.of(job("ltx25-i2v", JobKind.VIDEO,
                "seconds", 8, "orientation", "portrait"));

        assertThat(landscape).containsEntry("frames", 121).containsEntry("width", 1216).containsEntry("height", 704)
                .containsEntry("halfWidth", 608).containsEntry("halfHeight", 352);
        assertThat(portrait).containsEntry("frames", 193).containsEntry("width", 704).containsEntry("height", 1216)
                .containsEntry("halfWidth", 352).containsEntry("halfHeight", 608);
    }

    @Test
    void framesSatisfyTheRulesOfTheModels() {
        for (int seconds = 2; seconds <= 8; seconds++) {
            int wan = (Integer) WorkflowValues.of(job("wan22-i2v-fastmove", JobKind.VIDEO, "seconds", seconds,
                    "orientation", "landscape")).get("frames");
            int ltx = (Integer) WorkflowValues.of(job("ltx25-i2v", JobKind.VIDEO, "seconds", seconds,
                    "orientation", "landscape")).get("frames");
            assertThat((wan - 1) % 4).isZero();
            assertThat((ltx - 1) % 8).isZero();
        }
    }

    @Test
    void halfSizesAreMultiplesOf32() {
        for (String orientation : new String[]{"landscape", "portrait"}) {
            Map<String, Object> values = WorkflowValues.of(job("ltx25-i2v", JobKind.VIDEO, "seconds", 5,
                    "orientation", orientation));
            assertThat((Integer) values.get("halfWidth") % 32).isZero();
            assertThat((Integer) values.get("halfHeight") % 32).isZero();
        }
    }
}
