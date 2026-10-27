package ee.gaile.imagegen.model.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads workflows/&lt;id&gt;.json and workflows/&lt;id&gt;.params.json from the classpath, once per mode */
@Component
@RequiredArgsConstructor
public class WorkflowRepository {

    private final ObjectMapper mapper;
    private final Map<String, WorkflowTemplate> templates = new ConcurrentHashMap<>();

    public WorkflowTemplate get(String id) {
        return templates.computeIfAbsent(id, this::load);
    }

    private WorkflowTemplate load(String id) {
        try {
            ObjectNode graph = (ObjectNode) read("workflows/" + id + ".json");
            JsonNode description = read("workflows/" + id + ".params.json");

            Map<String, ParamSpec> params = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : description.path("params").properties()) {
                List<String> targets = new ArrayList<>();
                entry.getValue().path("targets").forEach(target -> targets.add(target.asString()));
                JsonNode defaultValue = entry.getValue().get("default");
                params.put(entry.getKey(), new ParamSpec(List.copyOf(targets), defaultValue));
            }
            String output = description.path("output").asString();
            if (!graph.has(output)) {
                throw new IllegalStateException("Workflow " + id + " has no output node " + output);
            }
            String anchor = description.path("keepFace").asString("");
            return new WorkflowTemplate(id, graph, output, params,
                    anchor.isEmpty() ? null : FaceAnchor.valueOf(anchor));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the workflow " + id, e);
        }
    }

    private JsonNode read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        }
    }
}
