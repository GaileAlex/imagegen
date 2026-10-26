package ee.gaile.imagegen;

import ee.gaile.imagegen.model.ModelCatalogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole app starts with its real settings; ComfyUI is not there, which only means the websocket keeps retrying.
 */
@SpringBootTest
class ImagegenApplicationTests {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("imagegen.data-dir", () -> dataDir.toString());
        registry.add("imagegen.comfyui-url", () -> "http://localhost:1");
        registry.add("management.server.port", () -> "0");
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }

    @Autowired
    private ModelCatalogue catalogue;

    @Test
    void contextLoadsWithTheCatalogueOfTheContract() {
        assertThat(catalogue.list()).hasSize(8);
        assertThat(dataDir.resolve("jobs")).isDirectory();
    }
}
