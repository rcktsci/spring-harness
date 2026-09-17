package se.rocketscien.harness.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

/**
 * Полная чистка данных приложения между тестами (по корпоративному навыку тестирования):
 * вызывается из {@code BaseApplicationTest} после каждого теста. Порядок — от дочерних
 * таблиц к родительским (FK-каскады дублируют порядок, но явность дешевле рассуждений).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    public void cleanup() {
        var stopWatch = new StopWatch();
        stopWatch.start();

        jdbcTemplate.update("DELETE FROM session_message");
        jdbcTemplate.update("DELETE FROM session");
        jdbcTemplate.update("DELETE FROM agent");
        jdbcTemplate.update("DELETE FROM llm_model");
        jdbcTemplate.update("DELETE FROM llm_credentials");
        jdbcTemplate.update("DELETE FROM app_user");
        // shedlock НЕ чистим (D-46): удаление строк ломает in-memory-кэш живого LockProvider
        // («recently created» → только UPDATE → тихий скип взятия лока до конца JVM);
        // строки shedlock безвредны и остаются.

        stopWatch.stop();
        log.info("Database cleanup finished in {}.", stopWatch.shortSummary());
    }
}
