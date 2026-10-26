package ee.gaile.imagegen.service;

/** ComfyUI does not answer /system_stats (503 comfyui_unavailable) */
public class ComfyUnavailableException extends RuntimeException {

    public ComfyUnavailableException(String message) {
        super(message);
    }
}
