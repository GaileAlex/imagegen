package ee.gaile.imagegen.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A client param of a mode, as GET /api/models shows it.
 *
 * @param defaultValue Integer, Double or String by the type; null for SEED
 * @param min          Integer for INT, Double for FLOAT
 * @param required     true or null, so the flag is absent for optional params
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParamDefinition(String name, ParamType type, @JsonProperty("default") Object defaultValue, Number min,
                              Number max, Number step, List<String> options, Boolean required) {
}
