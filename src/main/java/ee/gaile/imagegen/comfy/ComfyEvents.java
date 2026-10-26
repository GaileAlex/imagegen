package ee.gaile.imagegen.comfy;

import java.util.function.Consumer;

/**
 * The websocket of ComfyUI as the gateway sees it: one connection for the whole app, which ComfyUI addresses by
 * client id (the same id goes with POST /prompt, otherwise the events of the prompt are not sent to this socket).
 */
public interface ComfyEvents {

    String clientId();

    /** The socket is open now; while it is not, the gateway polls /history */
    boolean isConnected();

    /**
     * @return a call that removes the listener
     */
    Runnable subscribe(Consumer<ComfyEvent> listener);
}
