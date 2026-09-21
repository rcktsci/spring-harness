package se.rocketscien.harness.api;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.session.AgentNotFoundException;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.WrongSessionKindException;
import se.rocketscien.harness.task.DependencyInvalidException;
import se.rocketscien.harness.task.ParamsSchemaInvalidException;
import se.rocketscien.harness.task.TaskAlreadyTerminalException;
import se.rocketscien.harness.task.TaskNotWaitingWebhookException;
import se.rocketscien.harness.task.TaskNotFoundException;
import se.rocketscien.harness.task.TriggerNotFoundException;
import se.rocketscien.harness.task.TriggerRevokedException;
import se.rocketscien.harness.task.WorkflowRevisionNotFoundException;
import se.rocketscien.harness.workflow.WorkflowGraphInvalidException;
import se.rocketscien.harness.workflow.WorkflowKeyAlreadyExistsException;
import se.rocketscien.harness.workflow.WorkflowNotFoundException;

import java.io.IOException;
import java.util.List;

/**
 * Все ошибки API — RFC 9457 Problem Details с {@code code} из каталога M1+M2
 * (api-contracts §0.2, §6); 422 — с {@code errors[]} {pointer, rule, message}.
 * Коды вне каталога — дефект реализации.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class ApiExceptionHandler {

    private final ApiProblemWriter problemWriter;

    @ExceptionHandler(ApiValidationException.class)
    public void validation(ApiValidationException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY,
                ProblemCodes.VALIDATION_FAILED, exception.getMessage(), exception.errors());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class})
    public void invalidBody(Exception exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY,
                ProblemCodes.VALIDATION_FAILED, "Тело/параметры запроса невалидны",
                errorsOf(exception));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public void sessionNotFound(SessionNotFoundException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.NOT_FOUND, ProblemCodes.SESSION_NOT_FOUND,
                exception.getMessage(), null);
    }

    @ExceptionHandler(AgentNotFoundException.class)
    public void agentNotFound(AgentNotFoundException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.NOT_FOUND, ProblemCodes.AGENT_NOT_FOUND,
                exception.getMessage(), null);
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public void taskNotFound(TaskNotFoundException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.NOT_FOUND, ProblemCodes.TASK_NOT_FOUND,
                exception.getMessage(), null);
    }

    /** Ключ/ревизия workflow не найдены (создание задачи, api-contracts §6: workflow-not-found). */
    @ExceptionHandler({WorkflowNotFoundException.class, WorkflowRevisionNotFoundException.class})
    public void workflowNotFound(RuntimeException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.NOT_FOUND, ProblemCodes.WORKFLOW_NOT_FOUND,
                exception.getMessage(), null);
    }

    /** Задача в терминале (SUCCEEDED|FAILED|'$CANCELLED') — resume/stop неприменимы (§6). */
    @ExceptionHandler(TaskAlreadyTerminalException.class)
    public void taskAlreadyTerminal(TaskAlreadyTerminalException exception,
                                    HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.CONFLICT, ProblemCodes.TASK_ALREADY_TERMINAL,
                exception.getMessage(), null);
    }

    /** Self-loop/цикл/неизвестный blocker (§6: 422 dependency-invalid, errors[]). */
    @ExceptionHandler(DependencyInvalidException.class)
    public void dependencyInvalid(DependencyInvalidException exception,
                                  HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.DEPENDENCY_INVALID,
                exception.getMessage(), errorsOfSchema(exception.getErrors()));
    }

    /** params против paramsSchema ревизии (§6: 422 params-schema, errors[]). */
    @ExceptionHandler(ParamsSchemaInvalidException.class)
    public void paramsSchemaInvalid(ParamsSchemaInvalidException exception,
                                    HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.PARAMS_SCHEMA,
                exception.getMessage(), errorsOfSchema(exception.getErrors()));
    }

    @ExceptionHandler(WrongSessionKindException.class)
    public void wrongSessionKind(WrongSessionKindException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.CONFLICT, ProblemCodes.WRONG_SESSION_KIND,
                exception.getMessage(), null);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public void payloadTooLarge(PayloadTooLargeException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.PAYLOAD_TOO_LARGE, ProblemCodes.PAYLOAD_TOO_LARGE,
                exception.getMessage(), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                 HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.METHOD_NOT_ALLOWED, ProblemCodes.METHOD_NOT_ALLOWED,
                "Метод не поддержан этим путём", null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public void notAcceptable(HttpMediaTypeNotAcceptableException exception,
                              HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.NOT_ACCEPTABLE, ProblemCodes.NOT_ACCEPTABLE,
                "Accept не содержит поддерживаемого представления", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void unsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                     HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ProblemCodes.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type не поддержан", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public void typeMismatch(MethodArgumentTypeMismatchException exception,
                             HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.VALIDATION_FAILED,
                "Параметр запроса невалиден",
                List.of(new ApiValidationException.ValidationError(
                        "/" + exception.getName(), "typeMismatch", "Значение параметра невалидно")));
    }

    /** Параметровые ограничения (@Min на @RequestParam) через @Validated-прокси интерфейса. */
    @ExceptionHandler(ConstraintViolationException.class)
    public void constraintViolation(ConstraintViolationException exception,
                                    HttpServletResponse response) throws IOException {
        List<ApiValidationException.ValidationError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiValidationException.ValidationError(
                        "/" + violation.getPropertyPath(),
                        violation.getConstraintDescriptor() == null ? "invalid"
                                : violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        violation.getMessage() == null ? "Значение параметра невалидно" : violation.getMessage()))
                .toList();
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY,
                ProblemCodes.VALIDATION_FAILED, "Параметры запроса невалидны", errors);
    }

    @ExceptionHandler(SignatureInvalidException.class)
    public void signatureInvalid(SignatureInvalidException exception, HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNAUTHORIZED, ProblemCodes.SIGNATURE_INVALID,
                exception.getMessage(), null);
    }

    /** Задачи нет либо она вне WAIT_WEBHOOK (§6; повторная доставка вебхука — тоже 409). */
    @ExceptionHandler(TaskNotWaitingWebhookException.class)
    public void taskNotWaitingWebhook(TaskNotWaitingWebhookException exception,
                                      HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.CONFLICT, ProblemCodes.TASK_NOT_WAITING_WEBHOOK,
                exception.getMessage(), null);
    }

    /** Триггер не найден (DELETE несуществующего; §6). */
    @ExceptionHandler(TriggerNotFoundException.class)
    public void triggerNotFound(TriggerNotFoundException exception,
                                HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.NOT_FOUND, ProblemCodes.TRIGGER_NOT_FOUND,
                exception.getMessage(), null);
    }

    /** Capability-URL триггера мёртв (revoked_at установлен; §6). */
    @ExceptionHandler(TriggerRevokedException.class)
    public void triggerRevoked(TriggerRevokedException exception,
                               HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.GONE, ProblemCodes.TRIGGER_REVOKED,
                exception.getMessage(), null);
    }

    /** Граф не прошёл валидатор (создание workflow/ревизии; §6: 422 graph-invalid, errors[]). */
    @ExceptionHandler(WorkflowGraphInvalidException.class)
    public void workflowGraphInvalid(WorkflowGraphInvalidException exception,
                                     HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.GRAPH_INVALID,
                exception.getMessage(), errorsOfSchema(exception.getErrors()));
    }

    /** Дубликат key workflow (§6 не имеет 409-конфликт-кода; отклонение dev D-пачки №5). */
    @ExceptionHandler(WorkflowKeyAlreadyExistsException.class)
    public void workflowKeyAlreadyExists(WorkflowKeyAlreadyExistsException exception,
                                         HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.VALIDATION_FAILED,
                exception.getMessage(), List.of(new ApiValidationException.ValidationError(
                        "/key", "key-unique", "Workflow с таким key уже существует")));
    }

    @ExceptionHandler(Exception.class)
    public void unexpected(Exception exception, HttpServletResponse response) throws IOException {
        log.error("Неожиданная ошибка обработки запроса", exception);
        problemWriter.write(response, HttpStatus.INTERNAL_SERVER_ERROR, null,
                "Внутренняя ошибка сервера", null);
    }

    private List<ApiValidationException.ValidationError> errorsOfSchema(List<JsonSchemaError> errors) {
        return errors.stream()
                .map(error -> new ApiValidationException.ValidationError(
                        error.pointer(), error.rule(), error.message()))
                .toList();
    }

    private List<ApiValidationException.ValidationError> errorsOf(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException valid) {
            return valid.getBindingResult().getFieldErrors().stream()
                    .map(ApiExceptionHandler::toValidationError)
                    .toList();
        }
        if (exception instanceof HandlerMethodValidationException validation) {
            return validation.getAllErrors().stream()
                    .map(error -> new ApiValidationException.ValidationError(
                            parameterPointer(error), "invalid", "Значение параметра невалидно"))
                    .toList();
        }
        return List.of(new ApiValidationException.ValidationError(
                "", "parse", "Тело не является корректным JSON для этого эндпоинта"));
    }

    private static ApiValidationException.ValidationError toValidationError(FieldError fieldError) {
        String rule = switch (fieldError.getCode() == null ? "" : fieldError.getCode()) {
            case "NotNull", "NotBlank", "NotEmpty" -> "required";
            case "Size" -> "minLength";
            case "Min" -> "minimum";
            case "Max" -> "maximum";
            case "Pattern" -> "pattern";
            default -> fieldError.getCode();
        };
        return new ApiValidationException.ValidationError(
                "/" + fieldError.getField(), rule,
                fieldError.getDefaultMessage() == null ? "Значение невалидно" : fieldError.getDefaultMessage());
    }

    private String parameterPointer(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        for (String code : codes == null ? new String[0] : codes) {
            int dot = code.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < code.length()) {
                return "/" + code.substring(dot + 1);
            }
        }
        return "";
    }
}
