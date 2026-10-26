package ee.gaile.imagegen.model.config;

import ee.gaile.imagegen.job.JobKind;

import java.util.List;

/** A mode of the catalogue as written in application.yml (imagegen.models) */
public record ModelConfig(String id, JobKind kind, String title, String description, int typicalSeconds,
                          List<ParamConfig> params) {
}
