package ee.gaile.imagegen.translate;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param url     Ollama that runs the translation model; empty = no translation (the texts go to ComfyUI as typed)
 * @param model   an uncensored model: a censored one refuses to translate an explicit prompt
 * @param timeout one translation, a cold load of the model on the CPU included
 */
@ConfigurationProperties(prefix = "imagegen.translator")
public record TranslatorProperties(String url, String model, Duration timeout) {
}
