package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.config.ImagegenProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ComfyClientConfig {

    /** A result video is streamed through this client, the other calls are short */
    @Bean
    public RestClient comfyClient(ImagegenProperties properties) {
        return client(properties, Duration.ofSeconds(5), Duration.ofSeconds(120));
    }

    /** For /system_stats only: "is ComfyUI up" must answer in 3 s, not wait for the long timeout */
    @Bean
    public RestClient comfyProbeClient(ImagegenProperties properties) {
        return client(properties, Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    private RestClient client(ImagegenProperties properties, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // aiohttp speaks HTTP/1.1: no attempt to upgrade the connection to HTTP/2
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(properties.comfyuiUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
