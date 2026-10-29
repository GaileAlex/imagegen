package ee.gaile.imagegen.job;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Progress of a running job as ComfyUI reports it over its websocket.
 *
 * @param value  step of the current sampler (0 while TRANSLATING or LOADING)
 * @param max    steps of the current sampler (0 while TRANSLATING or LOADING); video workflows have two samplers, the
 *               bar restarts
 * @param scene  a long video: the scene the phase belongs to (1-based); null for a single clip
 * @param scenes a long video: how many scenes it has; null for a single clip
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Progress(Phase phase, int value, int max, Integer scene, Integer scenes) {

    public static final Progress TRANSLATING = new Progress(Phase.TRANSLATING, 0, 0);
    public static final Progress LOADING = new Progress(Phase.LOADING, 0, 0);

    public Progress(Phase phase, int value, int max) {
        this(phase, value, max, null, null);
    }

    /** The same phase as a part of a long video */
    public Progress inScene(int number, int count) {
        return new Progress(phase, value, max, number, count);
    }

    public enum Phase {
        /** The Russian prompt is translated into English, before ComfyUI gets the job */
        TRANSLATING,
        /** A long video: the language model writes the prompts of its scenes */
        STORYBOARD,
        /** Running, no sampler step reported yet: the models load */
        LOADING,
        SAMPLING,
        /** After the last sampler step: VAE decode, video encode */
        DECODING,
        /** A long video between two scenes: the next scene waits for its turn in the queue */
        WAITING,
        /** A long video: the scenes are joined into one clip */
        JOINING
    }
}
