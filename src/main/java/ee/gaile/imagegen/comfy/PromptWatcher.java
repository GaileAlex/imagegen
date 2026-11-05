package ee.gaile.imagegen.comfy;

import ee.gaile.imagegen.job.Progress;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Follows the events of one prompt. The prompt id is only known when POST /prompt has answered, and ComfyUI may
 * start sending before that, so events seen before {@link #bind} are kept (a few) and replayed.
 */
class PromptWatcher implements Consumer<ComfyEvent> {

    private static final int MAX_EARLY_EVENTS = 100;
    /**
     * The events of a prompt that older ComfyUI sends without its id; any other event without one (the "status" of
     * the queue, the monitors of custom nodes) tells nothing of the prompt and is no sign that it is alive
     */
    private static final Set<String> PROMPT_EVENTS = Set.of("progress", "executing", "execution_success",
            "execution_error", "execution_interrupted");

    enum Status {
        SUCCESS,
        ERROR,
        INTERRUPTED
    }

    /**
     * @param message the exception message of ComfyUI for ERROR
     */
    record Outcome(Status status, String message) {
    }

    private final String outputNode;
    private final Consumer<Progress> progress;
    private final Clock clock;
    private final List<ComfyEvent> early = new ArrayList<>();
    private String promptId;
    private boolean sampled;
    /** The output node runs: its steps are the upscale of the video (UpscaleGraphs), not sampling */
    private boolean atOutput;
    private Outcome outcome;
    private Instant lastEvent;

    PromptWatcher(String outputNode, Consumer<Progress> progress, Clock clock) {
        this.outputNode = outputNode;
        this.progress = progress;
        this.clock = clock;
        this.lastEvent = clock.instant();
    }

    synchronized void bind(String id) {
        promptId = id;
        List<ComfyEvent> replay = List.copyOf(early);
        early.clear();
        replay.forEach(this::handle);
    }

    @Override
    public synchronized void accept(ComfyEvent event) {
        if (promptId == null) {
            if (early.size() < MAX_EARLY_EVENTS) {
                early.add(event);
            }
            return;
        }
        handle(event);
    }

    synchronized Outcome outcome() {
        return outcome;
    }

    /** The last time an event of this prompt arrived (or the watcher was created) */
    synchronized Instant lastEvent() {
        return lastEvent;
    }

    /** Waits until the outcome is known, an event arrives, or the time is up */
    synchronized void await(long millis) throws InterruptedException {
        if (outcome == null) {
            wait(millis);
        }
    }

    /** Takes the outcome from /history when the websocket missed the end of the prompt */
    synchronized void finish(Outcome fromHistory) {
        if (outcome == null) {
            outcome = fromHistory;
            notifyAll();
        }
    }

    private void handle(ComfyEvent event) {
        String eventPrompt = event.promptId();
        boolean ours = eventPrompt != null ? eventPrompt.equals(promptId) : PROMPT_EVENTS.contains(event.type());
        if (!ours || outcome != null) {
            return;
        }
        lastEvent = clock.instant();
        JsonNode data = event.data();
        switch (event.type()) {
            case "progress" -> {
                boolean upscale = atOutput || outputNode.equals(data.path("node").asString(""));
                sampled = true;
                progress.accept(new Progress(upscale ? Progress.Phase.UPSCALING : Progress.Phase.SAMPLING,
                        data.path("value").asInt(), data.path("max").asInt()));
            }
            case "executing" -> {
                JsonNode node = data.get("node");
                if (node == null || node.isNull()) {
                    // The older way to say "the prompt is done"
                    outcome = new Outcome(Status.SUCCESS, null);
                } else if (node.asString().equals(outputNode)) {
                    atOutput = true;
                    if (sampled) {
                        progress.accept(new Progress(Progress.Phase.DECODING, 0, 0));
                    }
                }
            }
            case "execution_success" -> outcome = new Outcome(Status.SUCCESS, null);
            case "execution_error" -> outcome = new Outcome(Status.ERROR, errorMessage(data));
            case "execution_interrupted" -> outcome = new Outcome(Status.INTERRUPTED, "interrupted");
            default -> {
                // execution_start, execution_cached, progress_state ... carry nothing the job reports
            }
        }
        notifyAll();
    }

    private String errorMessage(JsonNode data) {
        String message = data.path("exception_message").asString("").strip();
        String type = data.path("exception_type").asString("");
        if (message.isEmpty()) {
            return type.isEmpty() ? "ComfyUI reported an error" : type;
        }
        return message;
    }
}
