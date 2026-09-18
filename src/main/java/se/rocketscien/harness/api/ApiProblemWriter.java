package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * Единая запись RFC 9457-ответов с {@code code} и {@code errors[]} (api-contracts §0.2, §6).
 * Пишет тело напрямую в response: ручная запись исключает content-negotiation —
 * в том числе для 406, где Accept клиента принципиально не содержит представление ответа.
 */
@Component
@RequiredArgsConstructor
public class ApiProblemWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, HttpStatus status, String code, String detail,
                      List<ApiValidationException.ValidationError> errors) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setProperty("code", code);
        if (errors != null) {
            problemDetail.setProperty("errors", errors);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
