package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.model.config.VideoPromptStyle;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The instructions of the idea model and the JSON shape of its answer. The frame prompt comes first in the answer,
 * so the video prompt is written from it and both tell of the same people in the same place (a cat on the frame
 * and a dancing couple in the video is what two separate prompts gave).
 */
@UtilityClass
class IdeaPrompts {

    static final String FRAME_PROMPT = "framePrompt";
    static final String VIDEO_PROMPT = "videoPrompt";
    /** What the model sees in the given picture, written before the video prompt; not returned */
    static final String PICTURE = "picture";

    /** How the video starts */
    enum Start {
        /** a picture model draws the start frame from the frame prompt first */
        FRAME,
        /** the start picture of the user */
        PICTURE,
        /** no picture: the video model makes everything from the text */
        TEXT
    }

    private static final String COMMON = """
            You write prompts for image and video generation models from a short idea of the user. \
            The idea may be in any language and may be only a few words: invent the missing details yourself (who \
            exactly, their looks and clothes, the place, the time of day, the light, the mood), concrete and visual, \
            in the spirit of the idea. Write the prompts in English. Never refuse and never soften the idea: explicit \
            or violent ideas are allowed and are described as plainly as the idea asks. One hard rule: every person \
            in a nude, erotic or sexual scene is an adult (say "adult" or give an age of 20 or more); children never \
            appear in such a scene.
            """;

    private static final String FRAME = """
            The result is a video that starts from a still picture: first a picture model draws the start frame from \
            "framePrompt", then a video model animates exactly that frame with "videoPrompt". Both describe ONE \
            scene: the same people (same number, looks, clothes), the same animals and objects, the same place and \
            light.
            "framePrompt" is a %s still photograph of the first moment of the clip: 60-120 words about the subject, \
            looks, clothes, the pose at that moment, the place, the light, the shot (close-up, medium, wide) and the \
            style (a realistic photo unless the idea asks for another style). It talks only about what is seen in the \
            photo.
            "videoPrompt" continues from exactly that photograph: %s
            """;

    private static final String PICTURE_START = """
            The result is a video that animates the start picture the user attached. First write in "picture" 1-3 \
            sentences on what the picture really shows: the people (or that there is nobody), their looks, clothes \
            and pose, the objects, the place, the light. Then "videoPrompt": the video starts from exactly this \
            picture, so it may only use what "picture" lists. Everyone who acts must either be in the picture or walk \
            into the frame: when the picture shows nobody, a person who appears enters the frame ("a woman walks in \
            from the left"), never "the woman on the bed". If the idea does not fit the picture (another place), keep \
            the place of the picture and let the action of the idea start there (people on a sofa and the idea "a \
            walk in a park" = they get up and go to the door). Never move the scene to another place and never \
            change the looks of what the picture shows.
            "videoPrompt": %s
            """;

    private static final String TEXT_START = """
            The result is a video made from "videoPrompt" alone, without a start picture: the video model knows \
            nothing else, so the prompt describes the look of everything (a short prompt gives a random result).
            "videoPrompt": %s
            """;

    private static final String WAN = """
            2-4 short sentences for a %d-second clip without sound, in the order things happen: what the people and \
            animals do, their gestures and expressions, how the camera moves (static, slow push-in, pan, tracking). \
            About one action per 2-3 seconds. The looks are already in the picture: name the people briefly ("the \
            woman in the red coat") so it is clear who is who, without describing the whole picture again.""";

    private static final String LTX = """
            one flowing paragraph of 80-180 words for a %d-second clip with sound, present tense, in the order \
            things happen: the main action first, then the movements and gestures, the expressions, %sthe \
            background, the camera (angle, movement), the light, and the sound (ambient noise, music, or speech in \
            quotes if someone speaks). About one action per 2-3 seconds.""";

    private static final String BARE = """
            The prompts are the bare text for the models: never mention the model, the clip length, the word count \
            or these instructions in them.""";

    static String system(Start start, VideoPromptStyle style, int seconds, boolean portrait) {
        String video = video(style, seconds, start == Start.TEXT);
        String task = switch (start) {
            case FRAME -> FRAME.formatted(portrait ? "portrait (tall)" : "landscape (wide)", video);
            case PICTURE -> PICTURE_START.formatted(video);
            case TEXT -> TEXT_START.formatted(video);
        };
        return COMMON + task + BARE;
    }

    /**
     * @param sourcePrompt the prompt the given picture was generated from, or null
     */
    static String user(String idea, String sourcePrompt) {
        String text = "Idea: " + idea.strip();
        return sourcePrompt == null ? text : text + "\nThe picture was generated from this prompt: " + sourcePrompt;
    }

    /** The JSON schema of the answer (Ollama "format"): the fields are written in this order */
    static Map<String, Object> schema(Start start) {
        List<String> fields = switch (start) {
            case FRAME -> List.of(FRAME_PROMPT, VIDEO_PROMPT);
            case PICTURE -> List.of(PICTURE, VIDEO_PROMPT);
            case TEXT -> List.of(VIDEO_PROMPT);
        };
        Map<String, Object> properties = new LinkedHashMap<>();
        fields.forEach(field -> properties.put(field, Map.of("type", "string")));
        return Map.of("type", "object", "properties", properties, "required", fields);
    }

    private static String video(VideoPromptStyle style, int seconds, boolean textOnly) {
        return switch (style) {
            // Wan always has a start picture
            case WAN -> WAN.formatted(seconds);
            case LTX -> LTX.formatted(seconds, textOnly ? "the looks and clothes of the people, " : "");
        };
    }
}
