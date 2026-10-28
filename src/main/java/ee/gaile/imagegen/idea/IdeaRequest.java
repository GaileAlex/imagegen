package ee.gaile.imagegen.idea;

import java.util.UUID;

/**
 * The "request" part of POST /api/prompt-ideas.
 *
 * @param idea        a few words in any language ("люди гуляют в парке")
 * @param videoModel  the VIDEO mode the prompt is for
 * @param seconds     the length of the clip; null = the default of the mode
 * @param frame       a start frame is made by a picture model first: its prompt is written too; null = no
 * @param orientation landscape or portrait, the shape of that frame; null = landscape
 * @param sourceJobId the uploaded picture is the result of this job of the user: its prompt tells what it shows
 */
public record IdeaRequest(String idea, String videoModel, Integer seconds, Boolean frame, String orientation,
                          UUID sourceJobId) {
}
