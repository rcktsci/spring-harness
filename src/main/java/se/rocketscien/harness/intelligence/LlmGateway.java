package se.rocketscien.harness.intelligence;

import org.springframework.ai.chat.model.ChatModel;

import java.util.UUID;

/**
 * Контракт доступа к LLM (D-M1-1/D-M1-3): клиент модели собирается из БД-конфигурации
 * ({@code llm_model} + {@code llm_credentials}), автоконфигурация Spring AI не используется.
 * Реализация кэширует клиент по {@code llm_model.id} (MVP: актуализация кэша — рестартом процесса).
 */
public interface LlmGateway {

    /**
     * @return клиент модели {@code llmModelId}
     * @throws LlmConfigurationException если записи модели или связанных учётных данных нет
     *                                   (Turn завершается FAILED, старт процесса не затронут)
     */
    ChatModel chatModel(UUID llmModelId);
}
