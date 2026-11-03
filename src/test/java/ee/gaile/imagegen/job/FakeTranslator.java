package ee.gaile.imagegen.job;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Translates" a text by prefixing it with EN:, or fails when {@link #failing} is set.
 */
public class FakeTranslator implements PromptTranslator {

    public volatile boolean failing;
    /** No translation model is set: nothing is translated */
    public volatile boolean off;
    public volatile int calls;
    public volatile int unloads;

    @Override
    public Map<String, String> translate(Map<String, String> texts) {
        calls++;
        if (failing) {
            throw new JobFailedException("prompt translation failed");
        }
        if (off) {
            return Map.of();
        }
        Map<String, String> translations = new LinkedHashMap<>();
        texts.forEach((name, text) -> translations.put(name, "EN:" + text));
        return translations;
    }

    @Override
    public void unload() {
        unloads++;
    }
}
