package se.rocketscien.harness.relay;

/**
 * WS-close-коды релея (api-contracts §5.5, IANA-приватный диапазон 4000–4999).
 */
public final class RelayCloseCodes {

    public static final int UNAUTHENTICATED = 4401;
    public static final int PROTOCOL_ERROR = 4403;
    public static final int CONFLICT = 4409;

    private RelayCloseCodes() {
    }
}
