package se.rocketscien.harness.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Аудит mcp-конфигов агентов при загрузке (Q-1): управление ревизиями — вручную в БД
 * (D-39, API регистрации нет), поэтому «валидация при загрузке ревизии» реализована как
 * стартовый проход по {@code agent.tools_jsonb.mcp}: неизвестный сервер (нет в
 * {@code harness.mcp.servers}) — ERROR в лог. Не фатально (агент без живого mcp-сервера
 * упадёт runtime-ошибкой манифеста только при попытке хода — см. AgentTurnEngine);
 * при появлении API регистрации агентов (вне M3) проверка переносится туда как 422.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpAgentConfigAuditor {

    private final McpClientRegistry clients;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @EventListener(ApplicationReadyEvent.class)
    public void audit() {
        int broken = 0;
        // R-1: jsonb_exists — операторная форма `?` не годится (PgJDBC принимает её за
        // JDBC-плейсхолдер: `tools_jsonb $1 'mcp'`, запрос никогда не матчится)
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT key, tools_jsonb FROM agent WHERE jsonb_exists(tools_jsonb, 'mcp')");
        for (Map<String, Object> row : rows) {
            String agentKey = String.valueOf(row.get("key"));
            try {
                Object mcp = jsonMapper.readValue(
                        String.valueOf(row.get("tools_jsonb")), Map.class).get("mcp");
                if (!(mcp instanceof List<?> configs)) {
                    // R-2: сигнал Q-1 не теряем — не-каноническая форма mcp (не массив по спеке)
                    log.warn("MCP-аудит: агент '{}' — tools_jsonb.mcp не является массивом "
                            + "(каноническая форма — массив объектов server/include/exclude); "
                            + "конфиг пропущен", agentKey);
                    continue;
                }
                for (Object entry : configs) {
                    if (entry instanceof Map<?, ?> config
                            && config.get("server") instanceof String server
                            && !clients.isKnownServer(server)) {
                        log.error("MCP-аудит: агент '{}' ссылается на неизвестный MCP-сервер '{}' "
                                + "(harness.mcp.servers) — ход агента завершится FAILED", agentKey, server);
                        broken++;
                    }
                }
            } catch (Exception e) {
                log.warn("MCP-аудит: tools_jsonb агента {} не разобран: {}", agentKey, e.getMessage());
            }
        }
        if (broken > 0) {
            log.error("MCP-аудит: конфигураций с неизвестными серверами: {}", broken);
        }
    }
}
