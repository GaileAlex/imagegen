package ee.gaile.imagegen.model.workflow;

/**
 * How the scenes of a long video pass the sampled latent on, instead of the decoded last frames: "latentChain" of
 * workflows/&lt;id&gt;.params.json (SVI). The colours never go through pixels between the scenes this way.
 *
 * @param load the LoadLatent node of the latent of the scene before
 * @param into the node that takes it: WanImageToVideoSVIPro, its inputs prev_samples and motion_latent_count
 * @param save the SaveLatent node of the latent of this scene, for the next one
 */
public record LatentChain(String load, String into, String save) {
}
