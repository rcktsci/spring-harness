package se.rocketscien.harness;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * JWKS-стаб для тестов JWT-валидации на самоподписанных токенах (Nimbus).
 * Публикует техническое свойство {@code wiremock.jwks.url}; профиль {@code jwtmock}
 * использует его в harness.security.*.
 */
public class WireMockJwksInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String ISSUER = "https://jwtmock.issuer";

    public static final RSAKey SIGNING_KEY;

    private static final WireMockServer WIRE_MOCK;

    static {
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID("jwtmock-key-1")
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сгенерировать RSA-ключ для JWKS-стаба", e);
        }
        WIRE_MOCK = new WireMockServer(new WireMockConfiguration().dynamicPort());
        WIRE_MOCK.start();
        String jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString();
        WIRE_MOCK.stubFor(get(urlEqualTo("/jwks"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwks)));
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applicationContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "wiremock-jwks",
                Map.of("wiremock.jwks.url", WIRE_MOCK.baseUrl())
        ));
    }
}
