package se.rocketscien.harness.execution;

/**
 * Статус исполнения инструмента (agent-tools §5): {@code OK}/{@code ERROR} — результат вызова,
 * {@code CANCELLED}/{@code LOST} — синтетические (отмена, рестарт-скан, смерть контейнера),
 * {@code ASYNC_ACCEPTED} — зарезервировано под async-окно M3.
 */
public enum ToolStatus {
    OK,
    ERROR,
    ASYNC_ACCEPTED,
    CANCELLED,
    LOST
}
