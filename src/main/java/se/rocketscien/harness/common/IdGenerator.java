package se.rocketscien.harness.common;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Единая точка генерации идентификаторов (D-M1-9).
 *
 * <p>Контракт {@link #newUuidV7()}: UUID v7 (RFC 9562, генератор владельца {@link UUIDv7Generator}) —
 * сортируем по времени (timestamp-префикс старших бит), но строгая монотонность в пределах одной
 * миллисекунды НЕ гарантируется; нигде в проекте для UUID она и не требуется.</p>
 *
 * <p>Контракт {@link #newUlid()}: монотонный ULID (26 символов, Crockford base32, unix-эпоха) для
 * {@code session_message.id} — строгая монотонность гарантируется: в пределах миллисекунды случайная
 * часть инкрементируется.</p>
 */
@Component
public class IdGenerator {

    private final UUIDv7Generator uuidV7Generator = new UUIDv7Generator();

    public UUID newUuidV7() {
        return uuidV7Generator.generate();
    }

    public String newUlid() {
        return UlidCreator.getMonotonicUlid().toString();
    }
}
