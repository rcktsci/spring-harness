package se.rocketscien.harness.execution;

/**
 * Источник инструкции, поднявшей Turn (D-52/D-59): гейт metaTools — инструмент {@code transition}
 * разрешён только при {@code USER}. В M2 единственная USER-точка входа — сообщение прямо в сессию
 * ({@code POST /api/v1/sessions/{id}/messages}); ход от TOOL_RESULT (продолжение после
 * инструмента) или от системного события (bootstrap STATE-сессии — seed-SYSTEM) metaTools
 * не порождает.
 */
public enum InstructionSource {
    USER,
    TOOL_RESULT,
    SYSTEM
}
