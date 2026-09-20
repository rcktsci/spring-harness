package se.rocketscien.harness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;
import se.rocketscien.harness.config.LimitsProperties;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HMAC-гейт capability-токенов вебхуков (api-contracts §4.4, ревью L-1): проверяет
 * {@code token = HMAC-SHA256(secret, kind + ':' + entityId)} <b>до</b> диспетчеризации и
 * HttpMessageConverter'ов — кривой токен получает {@code 401 signature-invalid} при любом
 * теле (битый JSON, отсутствие тела, превышение лимита), тело не парсится (спека
 * inbound-triggers «Webhook задачи», сценарий «кривой токен»).
 *
 * <p>Регистрация — Boot-регистрация {@code @Component} (прецедент {@code PayloadSizeFilter});
 * {@link Order} — раньше всех прочих фильтров (в т.ч. 413-лимита: аутентификация-гейт старше
 * размерного). {@code SecurityConfig} не тронут (D.2). Запрос оборачивается в
 * {@link ContentCachingRequestWrapper} — {@code WebhookHandlers} берёт из кэша размер тела
 * для {@code payloadSummary} без повторной сериализации (ревью L-5). Не-вебхуковые пути и
 * пути, не совпавшие с форматом capability-URL, проходят без проверки (там свои 404/405);
 * нестандартный {@code entityId} (не UUID) — 401, чтобы не раскрывать оракул валидности URL.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebhookTokenFilter extends OncePerRequestFilter {

    /** {@code /api/webhooks/{tasks|triggers}/{entityId}/{token}} — контракт §4.4. */
    private static final Pattern WEBHOOK_PATH =
            Pattern.compile("^/api/webhooks/(tasks|triggers)/([^/]+)/([^/]+)$");

    private static final Map<String, String> SEGMENT_TO_KIND =
            Map.of("tasks", "task", "triggers", "trigger");

    private final WebhookSignatureVerifier signatureVerifier;
    private final ApiProblemWriter problemWriter;
    private final LimitsProperties limits;

    public WebhookTokenFilter(WebhookSignatureVerifier signatureVerifier,
                              ApiProblemWriter problemWriter, LimitsProperties limits) {
        this.signatureVerifier = signatureVerifier;
        this.problemWriter = problemWriter;
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Matcher matcher = WEBHOOK_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            // не capability-URL (другой сегмент/глубина) — маршрут сам ответит 404
            chain.doFilter(request, response);
            return;
        }
        if (!signatureVerifier.verify(
                SEGMENT_TO_KIND.get(matcher.group(1)), entityIdOf(matcher.group(2)), matcher.group(3))) {
            reject(response);
            return;
        }
        // Spring 7: конструктор требует лимит кэша; бекстоп — harness.limits.body
        // (фактический барьер — PayloadSizeFilter: до кэширования тело им уже ограничено)
        chain.doFilter(new ContentCachingRequestWrapper(request, (int) limits.body().toBytes()), response);
    }

    /** Битый entityId не должен отличаться от битого токена — единый 401 (без оракула). */
    private static UUID entityIdOf(String rawEntityId) {
        try {
            return UUID.fromString(rawEntityId);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        problemWriter.write(response, HttpStatus.UNAUTHORIZED, ProblemCodes.SIGNATURE_INVALID,
                "Capability-токен не прошёл HMAC-проверку", null);
    }
}
