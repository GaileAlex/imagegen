package ee.gaile.imagegen.web;

import ee.gaile.imagegen.gpu.GpuState;
import ee.gaile.imagegen.gpu.UnloadPolicy;
import ee.gaile.imagegen.job.ComfyGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GpuController.class)
class GpuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UnloadPolicy policy;
    @MockitoBean
    private ComfyGateway gateway;

    @Test
    void heartbeatIsNoContent() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat").header("X-User-Id", "7"))
                .andExpect(status().isNoContent());

        verify(policy).heartbeat(7);
    }

    @Test
    void leaveIsNoContent() throws Exception {
        mockMvc.perform(post("/api/presence/leave").header("X-User-Id", "7"))
                .andExpect(status().isNoContent());

        verify(policy).leave(7);
    }

    @Test
    void presenceNeedsTheUserHeader() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_params"))
                .andExpect(jsonPath("$.message").value("The header X-User-Id is required"));
        mockMvc.perform(post("/api/presence/leave").header("X-User-Id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_params"));
    }

    @Test
    void unloadAnswersUnloaded() throws Exception {
        given(policy.requestUnload()).willReturn(UnloadPolicy.UnloadState.UNLOADED);

        mockMvc.perform(post("/api/unload"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{\"state\":\"UNLOADED\"}"));
    }

    @Test
    void unloadAnswersPendingWhileTheQueueRuns() throws Exception {
        given(policy.requestUnload()).willReturn(UnloadPolicy.UnloadState.PENDING);

        mockMvc.perform(post("/api/unload"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{\"state\":\"PENDING\"}"));
    }

    @Test
    void stateAddsWhetherComfyUiIsUp() throws Exception {
        UUID running = UUID.randomUUID();
        given(gateway.isUp()).willReturn(true);
        given(policy.state()).willReturn(new GpuState(true, "muse-txt2img", 2, running, false,
                Instant.parse("2026-10-26T12:40:00Z"), 1));

        mockMvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comfyuiUp").value(true))
                .andExpect(jsonPath("$.loaded").value(true))
                .andExpect(jsonPath("$.lastModel").value("muse-txt2img"))
                .andExpect(jsonPath("$.queueLength").value(2))
                .andExpect(jsonPath("$.runningJobId").value(running.toString()))
                .andExpect(jsonPath("$.unloadPending").value(false))
                .andExpect(jsonPath("$.idleUnloadAt").value("2026-10-26T12:40:00Z"))
                .andExpect(jsonPath("$.presentUsers").value(1));
    }

    @Test
    void stateWithComfyUiDown() throws Exception {
        given(gateway.isUp()).willReturn(false);
        given(policy.state()).willReturn(new GpuState(false, null, 0, null, false, null, 0));

        mockMvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comfyuiUp").value(false))
                .andExpect(jsonPath("$.runningJobId").value((Object) null));
    }
}
