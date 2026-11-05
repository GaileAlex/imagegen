package ee.gaile.imagegen.model.workflow;

/**
 * What a video workflow makes: "video" of workflows/&lt;id&gt;.params.json. Both models take frames = fps * seconds + 1
 * (4n+1 for Wan, 8n+1 for LTX).
 *
 * @param fps        frames per second of the model
 * @param width      size of a landscape video; portrait is the same turned around
 * @param tailFrames the last frames of a scene the next scene of a long video starts from: ~0.6 s, so the motion goes
 *                   on and not only the pose (a latent chain: the frames the two scenes share and the join blends);
 *                   one more than a whole number of latent frames (8n+1 for LTX, 4n+1 for Wan), so a scene with them
 *                   still has the frame count the model takes
 * @param upscaleTo  the long side of the video with the "upscale" param (the proportions stay); 0 = not given
 * @param smoothBy   the frames per frame of the video with the "smooth" param (frame interpolation); 1 = not given
 */
public record VideoProfile(int fps, int width, int height, int tailFrames, int upscaleTo, int smoothBy) {
}
