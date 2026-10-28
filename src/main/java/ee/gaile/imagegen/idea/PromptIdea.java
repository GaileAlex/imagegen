package ee.gaile.imagegen.idea;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The prompts written from an idea, in English.
 *
 * @param framePrompt the start frame for a picture model; only when a frame was asked for
 * @param videoPrompt the video, continuing exactly that frame (or the given picture)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromptIdea(String framePrompt, String videoPrompt) {
}
