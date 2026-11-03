package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.config.ImagegenProperties;
import ee.gaile.imagegen.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ComfyClientConfig {

    /** A result video is streamed through this client, the other calls are short */
    @Bean
    public RestClient comfyClient(ImagegenProperties properties) {
        return RestClients.of(properties.comfyuiUrl(), Duration.ofSeconds(5), Duration.ofSeconds(120));
    }

    /** For /system_stats and the holds: "is ComfyUI up" must answer in 3 s, not wait for the long timeout */
    @Bean
    public RestClient comfyProbeClient(ImagegenProperties properties) {
        return RestClients.of(properties.comfyuiUrl(), Duration.ofSeconds(3), Duration.ofSeconds(3));
    }
}
