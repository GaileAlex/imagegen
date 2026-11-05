package ee.gaile.imagegen.model;

import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * The names of the params the code itself looks at: the client params are listed in application.yml, the inputs of a
 * workflow in workflows/&lt;id&gt;.params.json.
 */
@UtilityClass
public class ParamNames {

    public static final String PROMPT = "prompt";
    /** Client param of a video: its length */
    public static final String SECONDS = "seconds";
    /** Client param of a video: landscape or portrait */
    public static final String ORIENTATION = "orientation";
    /** Client param of a video: the start image is given to the model again later in the clip, see FaceAnchor */
    public static final String KEEP_FACE = "keepFace";
    /** Client param of a soundless video model: its sound is made from the frames by another model, see SoundGraphs */
    public static final String SOUND = "sound";
    /** Client param of a video: the finished video is upscaled by a model, see UpscaleGraphs */
    public static final String UPSCALE = "upscale";
    /** Client param of a video: the finished video gets more frames per second by frame interpolation */
    public static final String SMOOTH = "smooth";
    /** Service param of a video made from the text alone: the workflow skips its start image */
    public static final String TEXT_ONLY = "textOnly";
    /** Client params the workflow has no input for: they only decide other values or the shape of the graph */
    public static final Set<String> CLIENT_ONLY = Set.of(SECONDS, ORIENTATION, KEEP_FACE, SOUND, UPSCALE, SMOOTH);
}
