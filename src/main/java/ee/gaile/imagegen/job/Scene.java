package ee.gaile.imagegen.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One scene of a long video. The video model makes the scenes one after another, each starting from the last frames
 * of the scene before, so they join into one unbroken clip.
 *
 * @param seconds the seconds the scene adds; the frames it starts from (the end of the scene before) are not counted
 * @param prompt  the video prompt of the scene; null while the storyboard of the job is still to be written
 * @param english the prompt in English when it was typed with Cyrillic in it, else null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Scene(int seconds, String prompt, String english) {

    public Scene withPrompt(String text) {
        return new Scene(seconds, text, null);
    }

    public Scene withEnglish(String text) {
        return new Scene(seconds, prompt, text);
    }

    /** The prompt the video model gets */
    @JsonIgnore
    public String modelPrompt() {
        return english != null ? english : prompt;
    }
}
