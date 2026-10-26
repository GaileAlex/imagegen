package ee.gaile.imagegen.comfy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ComfyFilesTest {

    @TempDir
    Path directory;

    @Test
    void deletesTheFileOfComfyUi() throws IOException {
        Path output = Files.createDirectories(directory.resolve("output/sub"));
        Path result = Files.writeString(output.resolve("a.png"), "x");

        new ComfyFiles(directory.toString()).delete("output", "sub", "a.png");

        assertThat(result).doesNotExist();
    }

    @Test
    void neverLeavesItsDirectory() throws IOException {
        Path outside = Files.writeString(directory.resolve("outside.txt"), "x");
        Path shared = Files.createDirectories(directory.resolve("io"));

        new ComfyFiles(shared.toString()).delete("output", "..", "../outside.txt");

        assertThat(outside).exists();
    }

    @Test
    void doesNothingWhenNotShared() {
        new ComfyFiles("").delete("output", "", "a.png");
    }
}
