package ee.gaile.imagegen.idea;

import java.util.Map;

/**
 * The model that writes the prompts.
 */
interface IdeaWriter {

    /** The model can run on the GPU, when the jobs lend it */
    boolean hasGpu();

    /**
     * @param picture a JPEG the model looks at, or null
     * @param onGpu   the GPU instance, lent by the queue; else the CPU one
     * @return the fields of {@link IdeaPrompts#schema}
     * @throws IdeaUnavailableException the model is not set, does not answer or answers no JSON
     */
    Map<String, String> write(String system, String user, byte[] picture, Map<String, Object> schema, boolean onGpu);
}
