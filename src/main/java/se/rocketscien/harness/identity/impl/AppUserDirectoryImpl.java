package se.rocketscien.harness.identity.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.identity.AppUserDirectory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AppUserDirectoryImpl implements AppUserDirectory {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    @Override
    public UUID idBySubject(String keycloakSubject) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM app_user WHERE keycloak_subject = ?", UUID.class, keycloakSubject);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "Пользователь %s отсутствует в app_user — синхронизация при запросе не отработала"
                            .formatted(keycloakSubject), e);
        }
    }

    @Override
    public Map<UUID, String> usernames(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new LinkedHashMap<>();
        namedJdbcTemplate.query(
                "SELECT id, username FROM app_user WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getString("username"));
                });
        return result;
    }
}
