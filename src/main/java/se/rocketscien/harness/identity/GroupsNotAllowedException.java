package se.rocketscien.harness.identity;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Гейт не пройден: аутентифицированный пользователь вне разрешённых групп — {@code 401 unauthenticated}.
 */
public class GroupsNotAllowedException extends AuthenticationException {

    public GroupsNotAllowedException(String message) {
        super(message);
    }
}
