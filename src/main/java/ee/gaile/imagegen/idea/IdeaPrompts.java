package ee.gaile.imagegen.idea;

import ee.gaile.imagegen.job.Scene;
import ee.gaile.imagegen.model.config.VideoPromptStyle;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    /** The people and animals in a picture without an idea, or "none"; not returned */
    static final String LIVING = "living";
    /** The action the model chose for a picture without an idea, written before the video prompt; not returned */
    static final String ACTION = "action";
    /** A long video: who is in it, with the phrases every scene calls them by; not returned */
    static final String CAST = "cast";
    /** A long video: the place and the light, the same in every scene; not returned */
    static final String PLACE = "place";
    /** A long video with sound: the background sound of every scene; not returned */
    static final String SOUND = "sound";
    /** A long video: the plan of the whole, written before its scenes; not returned */
    static final String STORY = "story";

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    /** A sentence on what is not there: "No person enters the frame.", "Nothing else moves." */
    private static final Pattern ABSENT = Pattern.compile("(no|nobody|nothing|there (is|are) no)\\b",
            Pattern.CASE_INSENSITIVE);

    /** How the video starts */
    enum Start {
        /** a picture model draws the start frame from the frame prompt first */
        FRAME,
        /** the start picture of the user */
        PICTURE,
        /** the start picture of the user without an idea: the model chooses what happens */
        PICTURE_ONLY,
        /** no picture: the video model makes everything from the text */
        TEXT;

        /** the model looks at the start picture */
        boolean seesPicture() {
            return this == PICTURE || this == PICTURE_ONLY;
        }
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
            picture, so it may only use what "picture" lists. %s
            "videoPrompt": %s
            """;

    private static final String WITH_IDEA = """
            Everyone who acts must either be in the picture or walk into the frame: when the picture shows nobody, a \
            person who appears enters the frame ("a woman walks in from the left"), never "the woman on the bed". If \
            the idea does not fit the picture (another place), keep the place of the picture and let the action of \
            the idea start there (people on a sofa and the idea "a walk in a park" = they get up and go to the \
            door). Never move the scene to another place and never change the looks of what the picture shows.""";

    // Kept apart from WITH_IDEA: its people walking in and "the woman on the bed" made the model fill an empty bed
    // with an invisible sleeper (a sinking pillow, a hand from under the duvet). A rule alone did not stop it, the
    // list of the living beings written first did (6 of 6 empty beds kept empty, test 2026-10-28); naming the action
    // first keeps it from settling for a blink and a camera move
    private static final String WITHOUT_IDEA = """
            There is no idea: you choose what happens. Write in "living" every person and animal the picture \
            shows, or "none". Then name the action in "action" (5-15 words), then write "videoPrompt".
            If "living" is "none", the picture is an empty place and stays empty for the whole clip: nobody comes \
            in, nothing is touched, and things do not move; "action" is only a camera move (a slow dolly, pan or \
            orbit) and a change of the light, plus what moves on its own outdoors (water, clouds, leaves in the \
            wind, flames, smoke). "videoPrompt" tells only what is seen and how the camera and the light move: \
            the video model takes "no person" for a person, so never name who or what is not there.
            Otherwise "action" is what those living beings would really do next: a lively action with clear \
            movement of the body, not only blinking, a head tilt or a camera move (a cat plays with a toy, chases \
            something, pounces, jumps down or runs off; a dog runs, jumps or catches a ball; a person laughs, waves, \
            talks, stands up or walks off, as the pose and the place suggest). A toy or a ball may come into the \
            frame for them; nobody new comes in. Things move only when one of them touches them.
            Never move the scene to another place and never change the looks of what the picture shows.""";

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

    // longer clips get longer prompts: 5 s 110-170 words, 12 s 180-240
    private static final String LTX = """
            one flowing paragraph of %d-%d words for a %d-second clip with sound, present tense, in the order \
            things happen: the main action first, then the movements and gestures, the expressions, %sthe \
            background, the camera (angle, movement), the light, and the sound (ambient noise, music, or speech in \
            quotes if someone speaks). About one action per 2-3 seconds.""";

    private static final String BARE = """
            The prompts are the bare text for the models: never mention the model, the clip length, the word count \
            or these instructions in them.""";

    // ---- a long video: one answer with all the scenes, each starting from the last frames of the one before ----

    private static final String STORYBOARD_FRAME = """
            First a picture model draws the start frame from "framePrompt", then a video model animates exactly that \
            frame scene by scene. "framePrompt" is a %s still photograph of the first moment of the video: 60-120 \
            words about the subject, looks, clothes, the pose at that moment, the place, the light, the shot \
            (close-up, medium, wide) and the style (a realistic photo unless the idea asks for another style); only \
            what is seen in the photo. The people and the place of the video are those of this photograph.
            """;

    private static final String STORYBOARD_PICTURE = """
            The video starts from the picture the user attached. First write in "picture" 1-3 sentences on what the \
            picture really shows: the people (or that there is nobody), their looks, clothes and pose, the objects, \
            the place, the light. The people and the place of the video are those of the picture. %s
            """;

    // the rules of WITHOUT_IDEA for a whole video instead of one action
    private static final String STORYBOARD_WITHOUT_IDEA = """
            There is no idea: you choose what happens. After "picture" write in "living" every person and animal \
            the picture shows, or "none".
            If "living" is "none", the place stays empty for the whole video: nobody comes in, nothing is touched, \
            and things do not move; the scenes are camera moves (a slow dolly, pan or orbit) and changes of the \
            light, plus what moves on its own outdoors (water, clouds, leaves in the wind, flames, smoke). The \
            video model takes "no person" for a person, so never name who or what is not there.
            Otherwise the story is what those living beings would really do: lively actions with clear movement of \
            the body, not only blinking, a head tilt or a camera move. A toy or a ball may come into the frame for \
            them; nobody new comes in. Things move only when one of them touches them.
            Never move the scene to another place and never change the looks of what the picture shows.""";

    private static final String STORYBOARD_TEXT = """
            There is no start picture: the video model makes the first scene from its prompt alone, so the prompt of \
            scene 1 describes the looks of everyone and of the place (a short prompt gives a random result).
            """;

    // 2026-10-29 test: a scene that ended with the man's back to the camera made the next one invent another face
    // and another view out of the window; the seams themselves did not show. The rule worked, but once a scene ended
    // with the rules themselves ("any person in the scene is an adult, aged 40, and his face is clearly visible")
    private static final String SCENES = """
            The video is %d seconds long and has no cuts: one unbroken shot made of %d scenes (%s). A video model \
            makes one scene at a time, and each scene starts from the very last frames of the scene before: \
            whatever those frames do not show is forgotten.
            Write first:
            - "cast": every person and animal of the video, each with a short phrase of looks and clothes ("the \
            bearded man in the black T-shirt"), or "none". Every scene calls them by exactly these phrases.
            - "place": the place, the time of day and the light in one sentence; they stay the same.
            %s- "story": what happens over the whole video in 2-5 sentences, scene by scene. Keep everything the \
            idea says and its order of events; invent what it leaves open. The story moves on: no scene repeats an \
            action of an earlier one.
            Then the scenes in their order: "scene1", "scene1End", "scene2", "scene2End" and so on up to the last \
            scene, which has no end. "sceneNEnd" is one sentence on where everyone is and what they do at the last \
            moment of scene N; scene N+1 starts exactly there.
            The video model sees only the prompt of its own scene, so every scene prompt stands on its own: it \
            begins with the first moment of its scene (scene 1: %s; a later scene: the end of the scene before), \
            calls everyone by the phrases of "cast", tells what happens in the order it happens, about one action \
            per 2-3 seconds (a scene with too little to do turns into slow motion), and keeps the place, the light \
            and the camera style. End every scene with the faces of the people turned to the camera or in profile, \
            never with their backs to the camera or their faces hidden, and with the place in view: the next scene \
            knows only those last frames and would invent a new face and a new room.
            A scene prompt only tells what is seen and heard in its scene: never copy these rules into it (no "his \
            face stays visible to the camera", no "only the man is in the room", no ages or "adult" unless the scene \
            is a sexual one), and stop when the scene is told. No scene fades out, cuts or ends: the next one goes on \
            from its last frame.
            Every scene prompt: %s
            """;

    private static final String SOUND_LINE = """
            - "sound": the background sound of the place (a quiet room, rain on the window, birds, street noise), \
            the same through the whole video.
            """;

    private static final String WAN_SCENE = """
            3-5 sentences (50-100 words) without sound: where everyone is at the first moment, what they do and \
            how they move, in order, their gestures and expressions, how the camera moves (static, slow push-in, \
            pan, tracking).""";

    private static final String LTX_SCENE = """
            one flowing paragraph of 120-220 words with sound, present tense: the first moment, the actions in \
            order, the movements and gestures, the expressions, the background, the camera (angle, movement), the \
            light, and the sound: the background of "sound" plus what happens in the scene. Speech in quotes only \
            if it ends within the scene; no music unless the idea asks for it.""";

    static String system(Start start, VideoPromptStyle style, int seconds, boolean portrait) {
        String video = video(style, seconds, start == Start.TEXT);
        String task = switch (start) {
            case FRAME -> FRAME.formatted(orientation(portrait), video);
            case PICTURE -> PICTURE_START.formatted(WITH_IDEA, video);
            case PICTURE_ONLY -> PICTURE_START.formatted(WITHOUT_IDEA, video);
            case TEXT -> TEXT_START.formatted(video);
        };
        return COMMON + task + BARE;
    }

    /**
     * The instructions for the storyboard of a long video: all its scenes in one answer, see
     * {@link #storyboardSchema}.
     *
     * @param lengths the seconds of each scene, in order
     */
    static String storyboard(Start start, VideoPromptStyle style, List<Integer> lengths, boolean portrait) {
        String opening = switch (start) {
            case FRAME -> STORYBOARD_FRAME.formatted(orientation(portrait));
            case PICTURE -> STORYBOARD_PICTURE.formatted(WITH_IDEA);
            case PICTURE_ONLY -> STORYBOARD_PICTURE.formatted(STORYBOARD_WITHOUT_IDEA);
            case TEXT -> STORYBOARD_TEXT;
        };
        String firstMoment = switch (start) {
            case FRAME -> "the photograph of \"framePrompt\"";
            case PICTURE, PICTURE_ONLY -> "exactly the picture";
            case TEXT -> "the opening of the video";
        };
        int total = lengths.stream().mapToInt(Integer::intValue).sum();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < lengths.size(); i++) {
            parts.add("scene " + (i + 1) + ": " + lengths.get(i) + " s");
        }
        boolean sound = style == VideoPromptStyle.LTX;
        String scenes = SCENES.formatted(total, lengths.size(), String.join(", ", parts), sound ? SOUND_LINE : "",
                firstMoment, sound ? LTX_SCENE : WAN_SCENE);
        return COMMON + opening + scenes + BARE;
    }

    /** The fields of a storyboard answer in the order they are written; the last scene has no end */
    static Map<String, Object> storyboardSchema(Start start, VideoPromptStyle style, int scenes) {
        List<String> fields = new ArrayList<>(switch (start) {
            case FRAME -> List.of(FRAME_PROMPT);
            case PICTURE -> List.of(PICTURE);
            case PICTURE_ONLY -> List.of(PICTURE, LIVING);
            case TEXT -> List.<String>of();
        });
        fields.add(CAST);
        fields.add(PLACE);
        if (style == VideoPromptStyle.LTX) {
            fields.add(SOUND);
        }
        fields.add(STORY);
        for (int number = 1; number <= scenes; number++) {
            fields.add(scene(number));
            if (number < scenes) {
                fields.add(scene(number) + "End");
            }
        }
        return schema(fields);
    }

    /** The answer field of a scene prompt (1-based) */
    static String scene(int number) {
        return "scene" + number;
    }

    /**
     * @param lengths the seconds of each scene, as the storyboard was asked for
     * @param empty   the picture shows nobody: the sentences on what is not there go, see {@link #withoutAbsent}
     * @return the scenes of a storyboard answer
     */
    static List<Scene> scenes(Map<String, String> answer, List<Integer> lengths, boolean empty) {
        List<Scene> scenes = new ArrayList<>(lengths.size());
        for (int i = 0; i < lengths.size(); i++) {
            String prompt = answer.get(scene(i + 1));
            scenes.add(new Scene(lengths.get(i), empty ? withoutAbsent(prompt) : prompt, null));
        }
        return scenes;
    }

    /**
     * @param idea         empty only for {@link Start#PICTURE_ONLY}
     * @param sourcePrompt the prompt the given picture was generated from, or null
     */
    static String user(String idea, String sourcePrompt) {
        String text = idea.isBlank()
                ? "No idea: choose the action yourself from the picture."
                : "Idea: " + idea.strip();
        return sourcePrompt == null ? text : text + "\nThe picture was generated from this prompt: " + sourcePrompt;
    }

    /** The JSON schema of the answer (Ollama "format"): the fields are written in this order */
    static Map<String, Object> schema(Start start) {
        List<String> fields = switch (start) {
            case FRAME -> List.of(FRAME_PROMPT, VIDEO_PROMPT);
            case PICTURE -> List.of(PICTURE, VIDEO_PROMPT);
            case PICTURE_ONLY -> List.of(PICTURE, LIVING, ACTION, VIDEO_PROMPT);
            case TEXT -> List.of(VIDEO_PROMPT);
        };
        return schema(fields);
    }

    private static Map<String, Object> schema(List<String> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        fields.forEach(field -> properties.put(field, Map.of("type", "string")));
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(fields));
    }

    private static String orientation(boolean portrait) {
        return portrait ? "portrait (tall)" : "landscape (wide)";
    }

    /** The "living" of the answer says the picture shows no people and no animals */
    static boolean nobodyIn(String living) {
        return living != null && living.strip().toLowerCase(Locale.ROOT).startsWith("none");
    }

    /**
     * The video prompt of an empty place without its sentences on what is not there: "No person enters the frame"
     * is a person to the video model, and the model kept the rule against them only now and then
     */
    static String withoutAbsent(String videoPrompt) {
        String kept = SENTENCE_END.splitAsStream(videoPrompt.strip())
                .filter(sentence -> !ABSENT.matcher(sentence).lookingAt())
                .collect(Collectors.joining(" "));
        return kept.isEmpty() ? videoPrompt : kept;
    }

    private static String video(VideoPromptStyle style, int seconds, boolean textOnly) {
        return switch (style) {
            // Wan always has a start picture
            case WAN -> WAN.formatted(seconds);
            case LTX -> LTX.formatted(60 + 10 * seconds, 120 + 10 * seconds, seconds,
                    textOnly ? "the looks and clothes of the people, " : "");
        };
    }
}
