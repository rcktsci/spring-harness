package se.rocketscien.harness.common.jsonschema;

/**
 * Ошибка валидации против ограниченного профиля JSON-Schema (D-58) — единая форма для
 * graph-invalid / params-schema / payload-schema: указатель на место нарушения, имя правила
 * и человекочитаемое сообщение (без утечки внутренних имён).
 *
 * @param pointer JSON-pointer фрагмента (например {@code /params/module} или {@code /states/1/code})
 * @param rule    имя правила ({@code type}, {@code enum}, {@code required}, {@code schema-invalid})
 * @param message пояснение
 */
public record JsonSchemaError(String pointer, String rule, String message) {
}
