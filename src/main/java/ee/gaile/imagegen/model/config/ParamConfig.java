package ee.gaile.imagegen.model.config;

import ee.gaile.imagegen.model.ParamType;

import org.springframework.boot.context.properties.bind.Name;

import java.util.List;

/**
 * A client param of a mode as written in application.yml. The default is a string there; unless it is given, the
 * default of workflows/&lt;id&gt;.params.json is used.
 */
public record ParamConfig(String name, ParamType type, @Name("default") String defaultValue, Double min, Double max,
                          Double step, boolean required, List<String> options) {
}
