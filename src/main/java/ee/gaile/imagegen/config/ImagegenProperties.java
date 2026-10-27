package ee.gaile.imagegen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param comfyuiUrl      base URL of the ComfyUI container
 * @param dataDir         job records, results and inputs (the volume imagegen-results)
 * @param idleUnload      the model is unloaded this long after the last job with nothing queued
 * @param presenceTimeout an open page that sent no heartbeat for this long counts as gone
 * @param retention       finished jobs and their files are deleted after this
 * @param jobTimeout      a job running longer is interrupted and fails
 */
@ConfigurationProperties(prefix = "imagegen")
public record ImagegenProperties(
        String comfyuiUrl,
        Path dataDir,
        Duration idleUnload,
        Duration presenceTimeout,
        Duration retention,
        Duration jobTimeout
) {
}
