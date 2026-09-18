package se.rocketscien.harness.api;

import se.rocketscien.harness.api.gen.model.ProblemCode;

/**
 * Строковые значения каталога кодов ошибок M1 (api-contracts §6) для сериализации в
 * {@code code} Problem Details; значения — из сгенерированного {@link ProblemCode}
 * (замороженная спека), расхождение каталога исключено.
 */
final class ProblemCodes {

    static final String VALIDATION_FAILED = ProblemCode.VALIDATION_FAILED.getValue();
    static final String UNAUTHENTICATED = ProblemCode.UNAUTHENTICATED.getValue();
    static final String SESSION_NOT_FOUND = ProblemCode.SESSION_NOT_FOUND.getValue();
    static final String AGENT_NOT_FOUND = ProblemCode.AGENT_NOT_FOUND.getValue();
    static final String WRONG_SESSION_KIND = ProblemCode.WRONG_SESSION_KIND.getValue();
    static final String PAYLOAD_TOO_LARGE = ProblemCode.PAYLOAD_TOO_LARGE.getValue();
    static final String METHOD_NOT_ALLOWED = ProblemCode.METHOD_NOT_ALLOWED.getValue();
    static final String NOT_ACCEPTABLE = ProblemCode.NOT_ACCEPTABLE.getValue();
    static final String UNSUPPORTED_MEDIA_TYPE = ProblemCode.UNSUPPORTED_MEDIA_TYPE.getValue();
    static final String SIGNATURE_INVALID = ProblemCode.SIGNATURE_INVALID.getValue();
    static final String NOT_IMPLEMENTED = ProblemCode.NOT_IMPLEMENTED.getValue();

    private ProblemCodes() {
    }
}
