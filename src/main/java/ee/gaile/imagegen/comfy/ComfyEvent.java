package ee.gaile.imagegen.comfy;

import tools.jackson.databind.JsonNode;

/**
 * A message of the ComfyUI websocket: {"type": "progress", "data": {...}}.
 */
public record ComfyEvent(String type, JsonNode data) {

    /** Prompt the event belongs to, null for events without one */
    public String promptId() {
        JsonNode id = data.get("prompt_id");
        return id != null && !id.isNull() ? id.asString() : null;
    }
}
