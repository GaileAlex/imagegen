package ee.gaile.imagegen.model.workflow;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * One entry of workflows/&lt;id&gt;.params.json.
 *
 * @param targets      "node.input" of the workflow the value goes to
 * @param defaultValue null when the file gives none
 * @param factor       a number value is multiplied by it before it goes to the targets (-1 turns the face variety
 *                     of the page into the negative weight of the SameFace Fix LoRA); null = as it is
 */
public record ParamSpec(List<String> targets, JsonNode defaultValue, Double factor) {
}
