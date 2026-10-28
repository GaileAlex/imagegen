package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.model.TestCatalogue;
import ee.gaile.imagegen.model.config.VideoPromptStyle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaIdeaWriterTest {

    private final OllamaIdeaWriter writer = new OllamaIdeaWriter(
            new IdeaProperties("", "", "m", Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), 0.9),
            TestCatalogue.MAPPER);

    @Test
    void takesTheFieldsOfTheSchemaInItsOrder() {
        Map<String, Object> schema = IdeaPrompts.schema(IdeaPrompts.Start.FRAME);

        Map<String, String> fields = writer.fields(
                "{\"videoPrompt\":\" They walk. \",\"framePrompt\":\"A park.\",\"extra\":\"x\"}", schema);

        assertThat(fields).containsExactly(Map.entry("framePrompt", "A park."), Map.entry("videoPrompt", "They walk."));
    }

    @Test
    void aMissingFieldOrNoJsonIsNoAnswer() {
        Map<String, Object> schema = IdeaPrompts.schema(IdeaPrompts.Start.FRAME);

        assertThatThrownBy(() -> writer.fields("{\"framePrompt\":\"A park.\",\"videoPrompt\":\" \"}", schema))
                .isInstanceOf(IdeaUnavailableException.class);
        assertThatThrownBy(() -> writer.fields("Sorry, I can't", schema))
                .isInstanceOf(IdeaUnavailableException.class);
    }

    @Test
    void withoutAnUrlThereIsNoIdeaModel() {
        assertThatThrownBy(() -> writer.write("s", "u", null, IdeaPrompts.schema(IdeaPrompts.Start.TEXT), false))
                .isInstanceOf(IdeaUnavailableException.class);
        assertThat(writer.hasGpu()).isFalse();
        // nothing to ask, nothing to fail
        writer.leaveGpu();
    }

    @Test
    void theInstructionsFitTheStartAndTheModel() {
        String frame = IdeaPrompts.system(IdeaPrompts.Start.FRAME, VideoPromptStyle.WAN,
                4, false);
        assertThat(frame).contains("\"framePrompt\" is a landscape (wide) still photograph")
                .contains("4-second clip without sound")
                .contains("every person in a nude, erotic or sexual scene is an adult")
                .doesNotContain("%");
        String picture = IdeaPrompts.system(IdeaPrompts.Start.PICTURE,
                VideoPromptStyle.LTX, 6, false);
        assertThat(picture).contains("Never move the scene to another place")
                .doesNotContain("the looks and clothes of the people");
    }
}
