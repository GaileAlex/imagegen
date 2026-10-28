package ee.gaile.imagegen.idea;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param gpuUrl       Ollama on the GPU: ~7-17 s an idea instead of 25-50 s, used whenever no job has the GPU;
 *                     empty = always the CPU
 * @param url          Ollama on the CPU, for the time a job has the GPU; empty = no prompt ideas there
 * @param model        an uncensored model that sees pictures, present in both instances: a censored one refuses
 *                     explicit ideas
 * @param timeout      one idea, a cold load of the model included
 * @param gpuKeepAlive how long the model stays on the GPU after an idea, for "another variant"; a job sends it away
 *                     earlier
 * @param keepAlive    the same on the CPU; it unloads itself, as no job may follow
 * @param temperature  high enough that "another variant" gives another scene
 */
@ConfigurationProperties(prefix = "imagegen.idea")
public record IdeaProperties(String gpuUrl, String url, String model, Duration timeout, Duration gpuKeepAlive,
                             Duration keepAlive, double temperature) {
}
