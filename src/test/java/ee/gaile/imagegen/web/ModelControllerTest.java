package ee.gaile.imagegen.web;

import ee.gaile.imagegen.model.ModelCatalogue;
import ee.gaile.imagegen.model.TestCatalogue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ModelController.class)
class ModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelCatalogue catalogue;

    @Test
    void listsTheModesInTheShapeOfTheContract() throws Exception {
        given(catalogue.list()).willReturn(TestCatalogue.get().list());

        mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$[0].id").value("muse-txt2img"))
                .andExpect(jsonPath("$[0].kind").value("IMAGE"))
                .andExpect(jsonPath("$[0].needsImage").value(false))
                .andExpect(jsonPath("$[0].needsMask").value(false))
                .andExpect(jsonPath("$[0].outputType").value("image/png"))
                .andExpect(jsonPath("$[0].typicalSeconds").value(50))
                .andExpect(jsonPath("$[0].params[0].name").value("prompt"))
                .andExpect(jsonPath("$[0].params[0].type").value("TEXT"))
                .andExpect(jsonPath("$[0].params[0].required").value(true))
                .andExpect(jsonPath("$[0].params[2].default").value(16))
                .andExpect(jsonPath("$[0].params[2].min").value(8))
                .andExpect(jsonPath("$[0].params[3].step").value(0.1))
                .andExpect(jsonPath("$[0].params[6].type").value("SEED"))
                .andExpect(jsonPath("$[0].params[6].default").doesNotExist())
                .andExpect(jsonPath("$[3].kind").value("INPAINT"))
                .andExpect(jsonPath("$[3].needsMask").value(true))
                .andExpect(jsonPath("$[6].outputType").value("video/mp4"))
                .andExpect(jsonPath("$[6].params[3].options[1]").value("portrait"));
    }
}
