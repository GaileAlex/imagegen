package ee.gaile.imagegen.job;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates the Russian texts of a job into English before it runs: the image and video models are trained on
 * English prompts. Only the worker thread of {@link JobQueue} calls {@link #translate}.
 */
public interface PromptTranslator {

    /**
     * @param texts param name -> text, each with Cyrillic in it
     * @return param name -> English text, for every given param
     * @throws JobFailedException when the translation model fails or answers nothing
     */
    Map<String, String> translate(Map<String, String> texts);

    /** Frees the memory of the translation model; called with the unload of the GPU model. */
    void unload();

    /**
     * @return the text params of the job with Cyrillic in them; English, Chinese (the Wan default negative prompt)
     * and the like stay as they are
     */
    static Map<String, String> cyrillicTexts(Map<String, Object> params) {
        Map<String, String> texts = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (value instanceof String text && text.codePoints()
                    .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC)) {
                texts.put(name, text);
            }
        });
        return texts;
    }
}
