package se.rocketscien.harness.session;

/**
 * Слушатель событий журнала сессии: {@link SessionStore} уведомляет всех зарегистрированных
 * слушателей после коммита дописи. Реализации обязаны быть неблокирующими и устойчивыми к
 * ошибкам (исключение одного слушателя не влияет на остальных).
 */
public interface SessionEventListener {

    void onEvent(SessionEvent event);
}
