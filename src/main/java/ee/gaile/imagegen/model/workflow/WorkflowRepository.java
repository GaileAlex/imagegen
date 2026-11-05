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
            String decoded = description.path("decoded").asString("");
            if (!decoded.isEmpty() && !graph.has(decoded)) {
                throw new IllegalStateException("Workflow " + id + " has no decoded node " + decoded);
            }
            String scenes = description.path("scenes").asString("");
            return new WorkflowTemplate(id, graph, output, params,
                    anchor.isEmpty() ? null : FaceAnchor.valueOf(anchor), decoded.isEmpty() ? null : decoded,
                    video(id, description.get("video")), scenes.isEmpty() ? null : scenes,
                    latentChain(id, graph, description.get("latentChain")));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the workflow " + id, e);
        }
    }

    private static LatentChain latentChain(String id, ObjectNode graph, JsonNode chain) {
        if (chain == null || chain.isNull()) {
            return null;
        }
        LatentChain nodes = new LatentChain(chain.path("load").asString(""), chain.path("into").asString(""),
                chain.path("save").asString(""));
        for (String node : List.of(nodes.load(), nodes.into(), nodes.save())) {
            if (!graph.has(node)) {
                throw new IllegalStateException("Workflow " + id + ": \"latentChain\" names no node " + node);
            }
        }
        return nodes;
    }

    private static VideoProfile video(String id, JsonNode video) {
        if (video == null || video.isNull()) {
            return null;
        }
        VideoProfile profile = new VideoProfile(video.path("fps").asInt(0), video.path("width").asInt(0),
                video.path("height").asInt(0), video.path("tailFrames").asInt(0), video.path("upscaleTo").asInt(0),
                video.path("smoothBy").asInt(1));
        if (profile.fps() <= 0 || profile.width() <= 0 || profile.height() <= 0 || profile.tailFrames() <= 0) {
            throw new IllegalStateException("Workflow " + id + ": \"video\" needs fps, width, height and tailFrames");
        }
        if (profile.upscaleTo() < 0 || profile.smoothBy() < 1) {
            throw new IllegalStateException("Workflow " + id + ": \"video\" has a bad upscaleTo or smoothBy");
        }
        return profile;
    }

    private JsonNode read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        }
    }
}
