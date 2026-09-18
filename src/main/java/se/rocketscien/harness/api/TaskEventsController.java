package se.rocketscien.harness.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * SSE-поток событий задачи — ручной SseEmitter по api-contracts §3.2; сгенерированный
 * интерфейс исключён из генерации (SSE-тег TaskEvents исключён, D-M1-8), путь/параметры
 * держатся по замороженной спеке. Реализация — пачка J (TaskWakeBroadcaster, снапшот
 * task.status + taskEventSeq, курсор task_event_seq, Last-Event-ID приоритетнее ?since=).
 */
@RestController
public class TaskEventsController {

    private static final String STUB = "D.2: stub — реализация в пачках H/I/J/K/L";

    @GetMapping(path = "/api/v1/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskEvents(
            @PathVariable("id") UUID id,
            @RequestParam(name = "since", required = false, defaultValue = "0") Long since,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        throw new ApiNotImplementedException(STUB);
    }
}
