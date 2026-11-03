package ee.gaile.imagegen.service;

import ee.gaile.imagegen.job.InvalidParamsException;
import lombok.experimental.UtilityClass;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** The JSON "request" part of the multipart requests of the API */
@UtilityClass
public class RequestParts {

    /**
     * @return the request; null for a JSON null
     * @throws InvalidParamsException the part is missing or is not JSON of the type
     */
    public static <T> T read(ObjectMapper mapper, String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new InvalidParamsException("The request part is missing");
        }
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new InvalidParamsException("The request is not valid JSON of the expected shape");
        }
    }
}
