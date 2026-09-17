package se.rocketscien.harness.session;

/**
 * Род сессии (data-model §5): FREE — пользовательская, STATE — принадлежит паре «задача × состояние».
 */
public enum SessionKind {
    FREE,
    STATE
}
