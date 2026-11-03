package ee.gaile.imagegen.config;

import lombok.experimental.UtilityClass;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** The HTTP clients of the containers next to this one: ComfyUI and the Ollama instances */
@UtilityClass
public class RestClients {

    public static RestClient of(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // aiohttp and Ollama speak HTTP/1.1: no attempt to upgrade the connection to HTTP/2
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
