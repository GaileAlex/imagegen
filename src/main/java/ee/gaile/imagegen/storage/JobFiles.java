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
        return directory.resolve(inputName(id));
    }

    public Path mask(UUID id) {
        return directory.resolve(maskName(id));
    }

    /** The small picture of the result for the history cards, made on the first request (Thumbnails) */
    public Path thumbnail(UUID id) {
        return directory.resolve(id + ".thumbnail.jpg");
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

    /** A scene of a long video (1-based), kept until the scenes are joined */
    public Path scene(UUID id, int number) {
        return directory.resolve(sceneName(id, number));
    }

    /** The last frames of the latest scene of a long video (an animated png): the next scene starts from them */
    public Path tail(UUID id) {
        return directory.resolve(tailName(id));
    }

    /*
     * The names of the files that go to ComfyUI as they are: the workflow names them in its inputs, so they are the
     * same here and in its input folder.
     */

    public static String inputName(UUID id) {
        return id + ".input.png";
    }

    public static String maskName(UUID id) {
        return id + ".mask.png";
    }

    public static String sceneName(UUID id, int number) {
        return id + ".scene" + number + ".mp4";
    }

    public static String tailName(UUID id) {
        return id + ".tail.png";
    }

    /** The scenes and the tail of a long video: no use once joined, failed or cancelled */
    public void deleteScenes(UUID id) {
        delete(id + ".scene*.mp4");
        delete(tailName(id));
    }

    /** The record and every file of the job */
    public void deleteAll(UUID id) {
        delete(id + ".*");
    }

    private void delete(String glob) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, glob)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + glob, e);
        }
    }
}
