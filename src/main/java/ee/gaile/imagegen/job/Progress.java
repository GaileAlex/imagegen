package ee.gaile.imagegen.job;

/**
 * Progress of a running job as ComfyUI reports it over its websocket.
 *
 * @param value step of the current sampler (0 while LOADING)
 * @param max   steps of the current sampler (0 while LOADING); video workflows have two samplers, the bar restarts
 */
public record Progress(Phase phase, int value, int max) {

    public static final Progress LOADING = new Progress(Phase.LOADING, 0, 0);

    public enum Phase {
        /** Running, no sampler step reported yet: the models load */
        LOADING,
        SAMPLING,
        /** After the last sampler step: VAE decode, video encode */
        DECODING
    }
}
