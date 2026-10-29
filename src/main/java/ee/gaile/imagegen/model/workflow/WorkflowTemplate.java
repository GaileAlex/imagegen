package ee.gaile.imagegen.model.workflow;

import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * A ComfyUI workflow in API format with the description of its params.
 *
 * @param outputNode the node whose output is the result (SaveImage, SaveVideo)
 * @param faceAnchor  how the workflow keeps the face of the start image; null when it cannot
 * @param decodedNode a video: the node whose output is the decoded frames (its last frames start the next scene of a
 *                    long video); null when the workflow cannot be a scene
 */
public record WorkflowTemplate(String id, ObjectNode graph, String outputNode, Map<String, ParamSpec> params,
                               FaceAnchor faceAnchor, String decodedNode) {

    /** A copy to fill in: the template itself is shared */
    public ObjectNode newGraph() {
        return graph.deepCopy();
    }
}
