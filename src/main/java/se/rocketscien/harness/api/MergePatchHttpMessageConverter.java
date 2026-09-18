package se.rocketscien.harness.api;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * Converter для {@code application/merge-patch+json} (спека PATCH /sessions): читает тело как
 * JSON-дерево, захватывает его в {@link MergePatchBodyContext} и связывает в целевой тип.
 * Перехват — в публичном {@code read}: Spring 7 читает тело по ResolvableType (MethodParameter),
 * минуя {@code readInternal}. Регистрация — первой в цепочке: дефолтный Jackson-конвертер
 * Spring 7 тоже принимает {@code application/*+json} и молча потерял бы различие absent/null.
 * Чужой Content-Type до конвертера не доходит — PATCH отфильтрован consumes-условием маппинга.
 */
public final class MergePatchHttpMessageConverter extends AbstractJacksonHttpMessageConverter<ObjectMapper> {

    public static final MediaType MERGE_PATCH_JSON = new MediaType("application", "merge-patch+json");

    public MergePatchHttpMessageConverter(ObjectMapper mapper) {
        super(mapper, MERGE_PATCH_JSON);
    }

    @Override
    public Object read(ResolvableType type, HttpInputMessage inputMessage, Map<String, Object> hints)
            throws IOException, HttpMessageNotReadableException {
        Type source = type.getSource() instanceof MethodParameter parameter
                ? parameter.getGenericParameterType()
                : type.getType();
        ObjectMapper mapper = getMapper();
        JsonNode tree;
        try {
            tree = mapper.readTree(inputMessage.getBody());
        } catch (RuntimeException e) {
            throw new HttpMessageNotReadableException("Тело не является корректным JSON", e, inputMessage);
        }
        MergePatchBodyContext.capture(tree);
        try {
            return mapper.readValue(mapper.writeValueAsBytes(tree), getJavaType(source, null));
        } catch (RuntimeException e) {
            throw new HttpMessageNotReadableException("Тело не связывается с " + type, e, inputMessage);
        }
    }
}
