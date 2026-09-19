package se.rocketscien.harness.api;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.beans.Introspector;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Converter для {@code application/merge-patch+json} (спека PATCH /sessions): читает тело как
 * JSON-дерево, захватывает его в {@link MergePatchBodyContext} и связывает в целевой тип.
 * Перехват — в публичном {@code read}: Spring 7 читает тело по ResolvableType (MethodParameter),
 * минуя {@code readInternal}. Регистрация — первой в цепочке: дефолтный Jackson-конвертер
 * Spring 7 тоже принимает {@code application/*+json} и молча потерял бы различие absent/null.
 * Чужой Content-Type до конвертера не доходит — PATCH отфильтрован consumes-условием маппинга.
 *
 * <p>K-5: сбой связывания из-за shape-нарушения «скаляр/объект там, где в DTO коллекция»
 * (например {@code tags: "foo"}) — не общий parse-отказ, а явный 422 validation-failed с
 * {@code rule=array-required} и точным pointer'ом: контроллер до проверки не доходит, binding
 * выполняется здесь.</p>
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
            ApiValidationException shapeViolation = shapeViolationOf(type, tree, e);
            if (shapeViolation != null) {
                throw shapeViolation;
            }
            throw new HttpMessageNotReadableException("Тело не связывается с " + type, e, inputMessage);
        }
    }

    /**
     * K-5: top-level член дерева — не массив, а свойство целевого DTO — коллекция →
     * {@code 422 rule=array-required}. Прочие сбои связывания — не наша забота (null).
     */
    private static ApiValidationException shapeViolationOf(ResolvableType type, JsonNode tree,
                                                           RuntimeException cause) {
        if (tree == null || !tree.isObject() || cause == null) {
            return null;
        }
        Class<?> dto = type.getRawClass();
        if (dto == null) {
            return null;
        }
        for (var entry : tree.properties()) {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (value == null || value.isArray()) {
                continue;
            }
            if (isCollectionProperty(dto, name)) {
                return new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                        "/" + name, "array-required", name + " должен быть массивом")));
            }
        }
        return null;
    }

    /** Свойство {@code name} целевого DTO объявлено коллекцией (List/Set и пр.). */
    private static boolean isCollectionProperty(Class<?> dto, String name) {
        try {
            var descriptor = Introspector.getBeanInfo(dto).getPropertyDescriptors();
            for (var pd : descriptor) {
                if (pd.getName().equals(name) && pd.getReadMethod() != null) {
                    Type generic = pd.getReadMethod().getGenericReturnType();
                    return generic instanceof ParameterizedType parameterized
                            && Collection.class.isAssignableFrom((Class<?>) parameterized.getRawType());
                }
            }
        } catch (Exception ignored) {
            // интроспекция недоступна — считаем, что это не shape-нарушение коллекции
        }
        return false;
    }
}
