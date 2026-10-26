package ee.gaile.imagegen.comfy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FaceAnchorsTest {

    @Test
    void ltxGetsAGuideAboutEveryThreeSecondsOnLatentFrameBoundaries() {
        // 2 s: only the end; 5 s: the middle and the end; 8 s and 12 s: every 2.7-3 s
        assertThat(FaceAnchors.ltxGuideFrames(49)).containsExactly(-1);
        assertThat(FaceAnchors.ltxGuideFrames(121)).containsExactly(56, -1);
        assertThat(FaceAnchors.ltxGuideFrames(193)).containsExactly(64, 128, -1);
        assertThat(FaceAnchors.ltxGuideFrames(289)).containsExactly(72, 144, 216, -1);
    }
}
