package ee.gaile.imagegen.web;

import ee.gaile.imagegen.model.ModelCatalogue;
import ee.gaile.imagegen.model.ModelDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelCatalogue catalogue;

    @GetMapping
    public List<ModelDefinition> models() {
        return catalogue.list();
    }
}
