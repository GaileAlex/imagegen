package ee.gaile.imagegen.web;

import ee.gaile.imagegen.idea.PromptIdea;
import ee.gaile.imagegen.idea.PromptIdeaService;
import ee.gaile.imagegen.service.InvalidParamsException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * The prompts of a video written from a short idea (docs/CONTRACT.md "Prompt ideas"). The answer waits for the
 * model: 7-17 s on the GPU, 25-50 s on the CPU while a job has the GPU.
 */
@RestController
@RequestMapping("/api/prompt-ideas")
@RequiredArgsConstructor
public class IdeaController {

    private final PromptIdeaService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PromptIdea write(@RequestHeader("X-User-Id") long userId,
                            @RequestPart("request") String request,
                            @RequestPart(value = "image", required = false) MultipartFile image) {
        return service.write(userId, request, bytes(image));
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidParamsException("The upload could not be read");
        }
    }
}
