package ee.gaile.imagegen.model.config;

/**
 * The kind of prompt a video model follows best; the prompt ideas are written in it.
 */
public enum VideoPromptStyle {
    /** Wan 2.2: a few short sentences of motion and camera, the start image gives the looks; no sound */
    WAN,
    /** LTX 2.5: one long paragraph in time order with the camera, the light and the sound */
    LTX
}
