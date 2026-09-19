package se.rocketscien.harness.execution;

import java.time.Duration;
import java.util.UUID;

/**
 * Контракт нативных инструментов рабочего каталога (agent-tools §1, specs/workspace-tools).
 * Все пути — относительные, резолв от корня workspace сессии; выход за его пределы запрещён.
 */
public interface WorkspaceTools {

    /**
     * {@code offset}/{@code limit} — в БАЙТАХ UTF-8; граница может разрезать code point
     * (замещающий символ на краю) — зафиксировано в agent-tools §1 (GLM C-7).
     */
    ToolResult readFile(UUID sessionId, String path, Integer offset, Integer limit);

    ToolResult writeFile(UUID sessionId, String path, String content);

    ToolResult editFile(UUID sessionId, String path, String oldString, String newString, boolean replaceAll);

    ToolResult glob(UUID sessionId, String pattern);

    ToolResult grep(UUID sessionId, String pattern, String include);

    ToolResult bash(UUID sessionId, String command, Duration timeout, String cwd);

    /**
     * bash с областью отмены (execution-model §6): отмена прерывает in-flight команду убийством
     * процесса в контейнере, результат — CANCELLED. Реализация по умолчанию отмену не поддерживает.
     */
    default ToolResult bash(UUID sessionId, String command, Duration timeout, String cwd,
                            TurnCancellation cancellation) {
        return bash(sessionId, command, timeout, cwd);
    }

    /**
     * bash workflow-состояния BASH_SCRIPT (D-50, specs/workspace-tools «Task-контейнер»):
     * исполняется в <b>отдельном</b> task-контейнере {@code harness-task-<taskId>} — изолированно
     * от контейнеров сессий {@code harness-<sessionId>}; workspace монтируется из
     * {@code workspaceRoot/task-<taskId>}. stdout и stderr объединены; non-zero exit — не ошибка
     * инструмента (exitCode в результате); при превышении {@code timeout} процесс убивается,
     * {@code timedOut=true}.
     *
     * <p><b>Инвариант идемпотентности (D-36/R3)</b>: скрипт состояния может быть выполнен
     * повторно (крах процесса между входом в состояние и переходом) — side-effect способен
     * сработать дважды; скрипты состояний должны быть идемпотентны (клоны/пуллы — таковы).</p>
     */
    ToolResult executeBash(UUID taskId, String script, Duration timeout, String cwd);
}
