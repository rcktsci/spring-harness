package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.AgentsApi;
import se.rocketscien.harness.api.gen.model.AgentCatalog;
import se.rocketscien.harness.api.gen.model.AgentCatalogItem;
import se.rocketscien.harness.session.SessionStore;

/**
 * GET /agents (api-contracts §1): каталог агентов — последняя ревизия каждого ключа из БД;
 * управление ревизиями — вручную в БД (MVP, D-39).
 */
@RestController
@RequiredArgsConstructor
public class AgentsController implements AgentsApi {

    private final SessionStore sessionStore;

    @Override
    public ResponseEntity<AgentCatalog> listAgents() {
        var items = sessionStore.agentCatalog().stream()
                .map(agent -> {
                    AgentCatalogItem item = new AgentCatalogItem(agent.agentKey(), agent.name(), agent.rev());
                    if (agent.description() != null) {
                        item.setDescription(agent.description());
                    }
                    return item;
                })
                .toList();
        return ResponseEntity.ok(new AgentCatalog(items));
    }
}
