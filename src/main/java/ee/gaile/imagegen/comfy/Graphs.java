package ee.gaile.imagegen.comfy;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The pieces of a ComfyUI graph in API format the graph builders add */
@UtilityClass
class Graphs {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** An empty graph */
    static ObjectNode graph() {
        return NODES.objectNode();
    }

    /**
     * Adds the node (or replaces the one of this id).
     *
     * @return its inputs, to fill in
     */
    static ObjectNode node(ObjectNode graph, String id, String classType) {
        return graph.putObject(id).put("class_type", classType).putObject("inputs");
    }

    /** An input taken from an output of another node */
    static ArrayNode link(String node, int output) {
        return NODES.arrayNode().add(node).add(output);
    }
}
