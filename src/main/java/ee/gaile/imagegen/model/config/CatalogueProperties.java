package ee.gaile.imagegen.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** The part of the imagegen settings that lists the modes; the rest is in ImagegenProperties */
@ConfigurationProperties(prefix = "imagegen")
public record CatalogueProperties(List<ModelConfig> models) {
}
