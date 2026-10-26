package ee.gaile.imagegen.model.workflow;

/**
 * How a video workflow keeps the face of its start image when the job asks for it (keepFace): the start image is
 * given to the model once more later in the clip, so the face cannot drift away for good. The person has to come
 * back to the pose of the photo there, and the motion gets calmer. "keepFace" of workflows/&lt;id&gt;.params.json.
 */
public enum FaceAnchor {
    /** Wan: the start image is the last frame as well (WanFirstLastFrameToVideo) */
    WAN_LAST_FRAME,
    /** LTX: the start image is a guide frame every few seconds and at the end, on both stages (LTXVAddGuide) */
    LTX_GUIDES
}
