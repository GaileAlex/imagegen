package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.Scene;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The prompt of a long video with its scenes written out: every scene starts with a mark line "[Scene 2 · 10 s]"
 * and its prompt follows. The prompt ideas write it so; the user may change the texts and the seconds.
 */
@UtilityClass
public class ScenePrompts {

    /** "[Scene 2 · 10 s]" on a line of its own; any separator, "Сцена 2, 10 с" as well */
    private static final Pattern MARK = Pattern.compile(
            "^[ \\t]*\\[\\s*(?:scene|сцена|stseen)\\s*(\\d+)\\s*[·•.,:;-]?\\s*(\\d+)\\s*"
                    + "(?:s|sec|seconds|с|сек|sek)?\\.?\\s*]\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);

    /**
     * @return the scenes of a text with marks, in the order written (the numbers in the marks are not checked); text
     * before the first mark belongs to the first scene; empty when the text has no mark
     * @throws InvalidParamsException a scene without text
     */
    public static List<Scene> parse(String text) {
        if (text == null) {
            return List.of();
        }
        Matcher mark = MARK.matcher(text);
        List<int[]> marks = new ArrayList<>();
        while (mark.find()) {
            marks.add(new int[]{mark.start(), mark.end(), Integer.parseInt(mark.group(2))});
        }
        List<Scene> scenes = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) {
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
            String prompt = text.substring(marks.get(i)[1], end).strip();
            if (i == 0) {
                prompt = (text.substring(0, marks.getFirst()[0]).strip() + "\n" + prompt).strip();
            }
            if (prompt.isEmpty()) {
                throw new InvalidParamsException("Scene " + (i + 1) + " has no text");
            }
            scenes.add(new Scene(marks.get(i)[2], prompt, null));
        }
        return scenes;
    }

    /** The text {@link #parse} reads back */
    public static String format(List<Scene> scenes) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            parts.add("[Scene " + (i + 1) + " · " + scenes.get(i).seconds() + " s]\n" + scenes.get(i).prompt());
        }
        return parts.stream().collect(Collectors.joining("\n\n"));
    }

    /**
     * @return the length split into the fewest scenes of at most {@code longest} seconds, as even as whole seconds
     * allow, the longer ones first (25 s of 10 = 9, 8, 8)
     */
    public static List<Integer> split(int seconds, int longest) {
        int count = (seconds + longest - 1) / longest;
        List<Integer> lengths = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lengths.add(seconds / count + (i < seconds % count ? 1 : 0));
        }
        return lengths;
    }
}
