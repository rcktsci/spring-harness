package se.rocketscien.harness.common.jsonschema;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Валидатор ограниченного профиля JSON-Schema (D-58): ключевые слова {@code type}, {@code enum},
 * {@code required}, {@code properties}, {@code items}; обход ручной, без зависимостей. Полная
 * JSON-Schema — точка эволюции (тогда подключается полноценный валидатор).
 *
 * <p>Чистая функция: инстанс (Map/List/скаляр — распарсенный JSON) против схемы (Map).
 * Неизвестные ключевые слова схемы игнорируются (профиль — подмножество; {@code description},
 * {@code title} и пр. легальны). Все нарушения собираются (не первое).</p>
 */
public final class LimitedJsonSchemaValidator {

    private static final Set<String> SUPPORTED_TYPES =
            Set.of("string", "number", "integer", "boolean", "object", "array", "null");

    private LimitedJsonSchemaValidator() {
    }

    /**
     * Валидация инстанса против схемы ограниченного профиля.
     *
     * @param instance    распарсенный JSON-инстанс
     * @param schema      схема (Map из JSON); null трактуется как отсутствие схемы — валидно
     * @param basePointer указатель корня (например {@code /params}); ошибки получают потомки
     * @return список нарушений; пустой — инстанс валиден
     */
    public static List<JsonSchemaError> validate(Object instance, Map<String, Object> schema, String basePointer) {
        List<JsonSchemaError> errors = new ArrayList<>();
        if (schema == null) {
            return errors;
        }
        validateAgainst(instance, schema, basePointer, errors);
        return errors;
    }

    private static void validateAgainst(Object instance, Map<String, Object> schema, String pointer,
                                        List<JsonSchemaError> errors) {
        Object type = schema.get("type");
        if (type instanceof String typeValue && !matchesType(instance, typeValue)) {
            errors.add(new JsonSchemaError(pointer, "type",
                    "Ожидался тип %s, получено %s".formatted(typeValue, jsonTypeName(instance))));
            return;
        }

        Object allowedEnum = schema.get("enum");
        if (allowedEnum instanceof Collection<?> allowed && !containsEqual(allowed, instance)) {
            errors.add(new JsonSchemaError(pointer, "enum",
                    "Значение не входит в enum %s".formatted(allowed)));
            return;
        }

        if (instance instanceof Map<?, ?> asMap) {
            validateObject(asMap, schema, pointer, errors);
        } else if (instance instanceof List<?> asList) {
            validateArray(asList, schema, pointer, errors);
        }
    }

    private static void validateObject(Map<?, ?> instance, Map<String, Object> schema, String pointer,
                                       List<JsonSchemaError> errors) {
        if (schema.get("required") instanceof Collection<?> required) {
            for (Object name : required) {
                if (name instanceof String key && !instance.containsKey(key)) {
                    errors.add(new JsonSchemaError(
                            pointer + "/" + key, "required",
                            "Отсутствует обязательное поле %s".formatted(key)));
                }
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || !(entry.getValue() instanceof Map<?, ?> propertySchema)) {
                    continue;
                }
                if (instance.containsKey(key)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) propertySchema;
                    validateAgainst(instance.get(key), typed, pointer + "/" + key, errors);
                }
            }
        }
    }

    private static void validateArray(List<?> instance, Map<String, Object> schema, String pointer,
                                      List<JsonSchemaError> errors) {
        if (!(schema.get("items") instanceof Map<?, ?> itemsSchema)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) itemsSchema;
        for (int i = 0; i < instance.size(); i++) {
            validateAgainst(instance.get(i), typed, pointer + "/" + i, errors);
        }
    }

    private static boolean matchesType(Object instance, String expected) {
        return switch (expected) {
            case "string" -> instance instanceof String;
            case "number" -> instance instanceof Number;
            // H-7: целостность проверяем по типу значения, не через double-сравнение —
            // long > 2^53 терял бы точность в double
            case "integer" -> isIntegral(instance);
            case "boolean" -> instance instanceof Boolean;
            case "object" -> instance instanceof Map;
            case "array" -> instance instanceof List;
            case "null" -> instance == null;
            default -> true;
        };
    }

    private static boolean isIntegral(Object instance) {
        return instance instanceof Integer
                || instance instanceof Long
                || instance instanceof Short
                || instance instanceof Byte
                || instance instanceof BigInteger
                || (instance instanceof Double value && isWhole(value))
                || (instance instanceof Float value && isWhole(value));
    }

    private static boolean isWhole(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value);
    }

    /** Сравнение с enum: целые — по longValue (H-7), иначе по doubleValue. */
    private static boolean containsEqual(Collection<?> allowed, Object instance) {
        for (Object candidate : allowed) {
            if (candidate == instance) {
                return true;
            }
            if (candidate == null || instance == null) {
                continue;
            }
            if (candidate instanceof Number a && instance instanceof Number b) {
                if (isIntegral(candidate) && isIntegral(instance)) {
                    if (a.longValue() == b.longValue()) {
                        return true;
                    }
                } else if (a.doubleValue() == b.doubleValue()) {
                    return true;
                }
            }
            if (candidate.equals(instance)) {
                return true;
            }
        }
        return false;
    }

    private static String jsonTypeName(Object instance) {
        if (instance == null) {
            return "null";
        }
        if (instance instanceof String) {
            return "string";
        }
        if (instance instanceof Number) {
            return "number";
        }
        if (instance instanceof Boolean) {
            return "boolean";
        }
        if (instance instanceof Map) {
            return "object";
        }
        if (instance instanceof List) {
            return "array";
        }
        return instance.getClass().getSimpleName();
    }
}
