package ee.gaile.imagegen.web;

import ee.gaile.imagegen.job.InvalidParamsException;
import lombok.experimental.UtilityClass;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** The file parts of the multipart requests */
@UtilityClass
class Uploads {

    /**
     * @return the bytes of the part, null when it was not sent
     * @throws InvalidParamsException the upload could not be read
     */
    static byte[] bytes(MultipartFile file) {
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
