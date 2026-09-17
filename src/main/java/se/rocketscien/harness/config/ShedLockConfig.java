package se.rocketscien.harness.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * ShedLock (D-M1-4): единый {@link LockProvider} на две роли — джоба POLL
 * ({@code @SchedulerLock} с {@code lockAtMostFor} = {@code harness.lock.job-ttl}) и программные
 * сессионные локи {@code sess-{id}} (см. {@code SessionLockManager}). {@code usingDbTime} —
 * время едино с критерием чистки {@code lock_until < now()}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${harness.lock.job-ttl}")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
