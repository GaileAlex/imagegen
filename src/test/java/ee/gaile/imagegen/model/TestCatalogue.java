package ee.gaile.imagegen.model;

import ee.gaile.imagegen.model.config.CatalogueProperties;
import ee.gaile.imagegen.model.workflow.WorkflowRepository;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The catalogue of the real application.yml and workflows, built without a Spring context.
 */
public final class TestCatalogue {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static ModelCatalogue catalogue;

    private TestCatalogue() {
    }

    public static synchronized ModelCatalogue get() {
        if (catalogue == null) {
            try {
                List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml"));
                CatalogueProperties properties = new Binder(ConfigurationPropertySources.from(sources.get(0)))
                        .bind("imagegen", CatalogueProperties.class).get();
                catalogue = new ModelCatalogue(properties, new WorkflowRepository(MAPPER));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return catalogue;
    }

    public static ModelDefinition model(String id) {
        return get().find(id).orElseThrow();
    }
}
