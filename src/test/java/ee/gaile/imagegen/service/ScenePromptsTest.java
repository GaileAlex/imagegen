package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenePromptsTest {

    @Test
    void theWrittenScenesAreReadBack() {
        List<Scene> scenes = List.of(new Scene(10, "He plays.\nHe smiles.", null), new Scene(8, "He stands up.", null));

        String text = ScenePrompts.format(scenes);

        assertThat(text).isEqualTo("[Scene 1 · 10 s]\nHe plays.\nHe smiles.\n\n[Scene 2 · 8 s]\nHe stands up.");
        assertThat(ScenePrompts.parse(text)).isEqualTo(scenes);
    }

    @Test
    void marksTypedByHandAreReadToo() {
        String text = "[сцена 1, 6 с]\nкот играет\r\n  [Scene 2 - 4 sec]  \r\nthe cat runs off\n[STSEEN 3: 5]\nkass magab";

        assertThat(ScenePrompts.parse(text)).extracting(Scene::seconds, Scene::prompt).containsExactly(
                org.assertj.core.groups.Tuple.tuple(6, "кот играет"),
                org.assertj.core.groups.Tuple.tuple(4, "the cat runs off"),
                org.assertj.core.groups.Tuple.tuple(5, "kass magab"));
    }

    @Test
    void textBeforeTheFirstMarkBelongsToTheFirstScene() {
        assertThat(ScenePrompts.parse("A sunny day.\n[Scene 1 · 5 s]\nHe waves.\n[Scene 2 · 5 s]\nHe goes."))
                .extracting(Scene::prompt).containsExactly("A sunny day.\nHe waves.", "He goes.");
    }

    @Test
    void aTextWithoutMarksHasNoScenes() {
        assertThat(ScenePrompts.parse("A man plays the guitar [softly] for 10 s")).isEmpty();
        assertThat(ScenePrompts.parse(null)).isEmpty();
    }

    @Test
    void anEmptySceneIsRefused() {
        assertThatThrownBy(() -> ScenePrompts.parse("[Scene 1 · 5 s]\nHe waves.\n[Scene 2 · 5 s]\n  \n"))
                .isInstanceOf(InvalidParamsException.class).hasMessageContaining("Scene 2");
    }

    @Test
    void aLengthIsSplitIntoTheFewestEvenScenes() {
        assertThat(ScenePrompts.split(30, 10)).containsExactly(10, 10, 10);
        assertThat(ScenePrompts.split(25, 10)).containsExactly(9, 8, 8);
        assertThat(ScenePrompts.split(13, 10)).containsExactly(7, 6);
        assertThat(ScenePrompts.split(30, 5)).containsExactly(5, 5, 5, 5, 5, 5);
        assertThat(ScenePrompts.split(7, 5)).containsExactly(4, 3);
    }
}
