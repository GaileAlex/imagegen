package ee.gaile.imagegen.translate;

import ee.gaile.imagegen.job.PromptTranslator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaTranslatorTest {

    @Test
    void picksOnlyTextsWithCyrillic() {
        Map<String, Object> params = Map.of("prompt", "кот on a sofa", "negativePrompt", "色调艳丽, blurry",
                "orientation", "portrait", "seconds", 5);

        assertThat(PromptTranslator.cyrillicTexts(params)).isEqualTo(Map.of("prompt", "кот on a sofa"));
    }

    @Test
    void stripsQuotesAroundTheAnswer() {
        assertThat(OllamaTranslator.clean(" \"a ginger cat\"\n")).isEqualTo("a ginger cat");
        assertThat(OllamaTranslator.clean("«a cat»")).isEqualTo("a cat");
        assertThat(OllamaTranslator.clean("a \"red\" dress")).isEqualTo("a \"red\" dress");
    }

    @Test
    void translatesNothingWithoutAnUrl() {
        OllamaTranslator translator = new OllamaTranslator(new TranslatorProperties("", "m", null));

        assertThat(translator.translate(Map.of("prompt", "кот"))).isEmpty();
        translator.unload();
    }
}
