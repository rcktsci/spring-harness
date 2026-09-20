package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.session.AgentNotFoundException;
import se.rocketscien.harness.session.AgentRevisionRepository;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.StateSessionService;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;

import java.util.UUID;

/**
 * Bootstrap AGENT-состояния (пачка J.3; execution-model §7.2 «состояние агентское»): задача
 * вошла в AGENT-state без STATE-сессии (EVENT-wake после перехода) → найти/создать STATE-сессию
 * пары (task, state) — {@link StateSessionService#findOrCreate}, атомарной вместе с seed — и
 * поднять wake сессии ({@code sess-{id}} — лок/очередь TurnManager'а, wake-шина M1).
 * Вызывается диспетчером движка на EVENT-wake; POLL ({@code task-scheduler}) страхует потерянные
 * события повторным wake. Идемпотентен: повторный bootstrap резюмирует сессию, tryStart
 * без незапрошенного батча — no-op.
 *
 * <p>Ревизия агента — {@code agent_key} состояния графа, последняя ревизия на момент входа;
 * пин фиксируется созданием сессии и при резюме не меняется (спека task-engine «STATE-сессии»).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentStateBootstrapper {

    private final TaskRegistry taskRegistry;
    private final TaskGraphReader graphs;
    private final StateSessionService stateSessions;
    private final AgentRevisionRepository agentRevisions;
    private final SessionStore sessionStore;
    private final TurnManager turnManager;

    /** Найти/создать STATE-сессию и поднять Turn; повторный вызов безопасен. */
    public void bootstrap(UUID taskId, String stateCode) {
        Task task = taskRegistry.get(taskId);
        TaskGraphReader.GraphState state = graphs.state(graphs.loadForTask(task), stateCode);
        UUID revisionId = resolveAgentRevision(state);
        Session session = stateSessions.findOrCreate(taskId, stateCode, revisionId);
        // Явный resume (O-2): вход в AGENT-состояние / resume задачи снимает персистентный
        // stop сессии (после TaskRegistry.stop флаг держится до явного старта). Для живой
        // задачи флаг и так false — сброс без эффекта. Сессия остановленной ('$CANCELLED')
        // задачи сюда не доходит: диспетчер ведёт её в handleStop.
        sessionStore.resetCancelRequested(session.id());
        log.info("Bootstrap AGENT-состояния '{}' задачи {} → сессия {}", stateCode, taskId, session.id());
        turnManager.tryStart(session.id());
    }

    private UUID resolveAgentRevision(TaskGraphReader.GraphState state) {
        if (!(state.raw().get("agent_key") instanceof String agentKey) || agentKey.isBlank()) {
            throw new IllegalStateException(
                    "AGENT-состояние '%s' без agent_key — граф невалиден".formatted(state.code()));
        }
        return agentRevisions.findFirstByAgentKeyOrderByRevDesc(agentKey)
                .orElseThrow(() -> new AgentNotFoundException(
                        "Агент '%s' состояния '%s' не найден".formatted(agentKey, state.code())))
                .getId();
    }
}
