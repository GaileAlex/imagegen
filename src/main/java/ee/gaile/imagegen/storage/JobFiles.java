package ee.gaile.imagegen.storage;

import ee.gaile.imagegen.config.ImagegenProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Where the files of a job live: all in {dataDir}/jobs, named by the job id, so the record, the inputs and the result
 * disappear together (docs/CONTRACT.md, "Storage").
 */
@Component
public class JobFiles {

    private final Path directory;

    @Autowired
    public JobFiles(ImagegenProperties properties) {
        this(properties.dataDir().resolve("jobs"));
    }

    public JobFiles(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + directory, e);
        }
    }

    public Path directory() {
        return directory;
    }

    public Path record(UUID id) {
        return directory.resolve(id + ".json");
    }

    public Path input(UUID id) {
        return directory.resolve(id + ".input.png");
    }

    public Path mask(UUID id) {
        return directory.resolve(id + ".mask.png");
    }

    /**
     * @param contentType image/png or video/mp4
     */
    public Path result(UUID id, String contentType) {
        return directory.resolve(id + "." + extension(contentType));
    }

    public static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "video/mp4" -> "mp4";
            default -> throw new IllegalArgumentException("Unsupported result type " + contentType);
        };
    }

    /** The record and every file of the job */
    public void deleteAll(UUID id) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, id + ".*")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the files of job " + id, e);
        }
    }
}
