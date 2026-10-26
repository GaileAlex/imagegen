package ee.gaile.imagegen.model.workflow;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * One entry of workflows/&lt;id&gt;.params.json.
 *
 * @param targets      "node.input" of the workflow the value goes to
 * @param defaultValue null when the file gives none
 */
public record ParamSpec(List<String> targets, JsonNode defaultValue) {
}
