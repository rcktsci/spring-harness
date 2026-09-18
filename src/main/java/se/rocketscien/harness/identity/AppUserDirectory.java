package se.rocketscien.harness.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Справочник {@code app_user} для API-слоя: резолв {@code keycloak_subject → id}
 * (атрибуция владельца/автора) и {@code id → username} (SessionDto.owner, MessageDto.author —
 * glossary §4: наружу светится username, subject остаётся внутренней идентичностью).
 * Пользователь синхронизируется {@code UserSyncFilter}-ом на каждом запросе — к моменту
 * вызова контроллера строка существует.
 */
public interface AppUserDirectory {

    /** {@code app_user.id} по {@code keycloak_subject}; отсутствует — IllegalStateException (сбой синхронизации). */
    UUID idBySubject(String keycloakSubject);

    /** username'ы по идентификаторам; отсутствующие в БД не входят в результат. */
    Map<UUID, String> usernames(Collection<UUID> userIds);
}
