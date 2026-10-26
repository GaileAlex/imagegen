package ee.gaile.imagegen.comfy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The input and output folders of ComfyUI, shared with this service through the volume imagegen-comfy-io. ComfyUI
 * never deletes an uploaded photo or a result by itself, and the API has no call for it: without this the users'
 * photos would pile up in the ComfyUI container. Empty directory = not shared (a local run outside Docker).
 */
@Slf4j
@Component
public class ComfyFiles {

    private final Path directory;

    public ComfyFiles(@Value("${imagegen.comfyui-io-dir:}") String directory) {
        this.directory = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    /**
     * @param type input or output, the folder names of ComfyUI
     */
    public void delete(String type, String subfolder, String filename) {
        if (directory == null) {
            return;
        }
        Path file = directory.resolve(type).resolve(subfolder).resolve(filename).normalize();
        if (!file.startsWith(directory)) {
            log.warn("Not deleting {}: outside {}", file, directory);
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", file, e.getMessage());
        }
    }
}
