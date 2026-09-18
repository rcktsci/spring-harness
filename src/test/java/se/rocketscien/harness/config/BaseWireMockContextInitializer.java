package se.rocketscien.harness.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.SneakyThrows;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.NonNull;

import java.io.IOException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * База WireMock-инициализаторов (по корпоративному навыку тестирования): серверы создаются
 * на динамических портах, публикуются свойствами {@code wiremock.<qualifier>.host/port/url}
 * и регистрируются бинами контекста с destroy-методом {@code stop}.
 */
@RequiredArgsConstructor
@Slf4j
public abstract class BaseWireMockContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Map<String, WireMockServer> WIREMOCK_SERVERS_BY_QUALIFIER = new HashMap<>();

    private final Set<String> beanNames;

    @Override
    public void initialize(@NonNull ConfigurableApplicationContext applicationContext) {
        if (applicationContext instanceof AnnotationConfigRegistry annotationConfigRegistry) {
            annotationConfigRegistry.register(WireMockRegistrarConfiguration.class);
        } else {
            throw new IllegalStateException("Контекст не является AnnotationConfigRegistry");
        }

        var environment = applicationContext.getEnvironment();

        beanNames.forEach(beanName -> WIREMOCK_SERVERS_BY_QUALIFIER.put(beanName, createStartedWireMockServer()));

        var testWireMockProperties = buildTestPropertyValuesMap();
        testWireMockProperties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> log.info("Setting property '{}' to '{}'.", entry.getKey(), entry.getValue()));

        TestPropertyValues
                .of(testWireMockProperties)
                .applyTo(environment);
    }

    @NonNull
    private Map<String, String> buildTestPropertyValuesMap() {
        Map<String, String> result = new HashMap<>();

        for (var entry : WIREMOCK_SERVERS_BY_QUALIFIER.entrySet()) {
            var beanName = entry.getKey();
            var wireMockServerPort = String.valueOf(entry.getValue().port());

            result.put("wiremock." + beanName + ".host", "localhost");
            result.put("wiremock." + beanName + ".port", wireMockServerPort);
            result.put("wiremock." + beanName + ".url", "http://localhost:" + wireMockServerPort);
        }

        return Collections.unmodifiableMap(result);
    }

    @NonNull
    private static WireMockServer createStartedWireMockServer() {
        var options = WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .asynchronousResponseEnabled(true)
                .asynchronousResponseThreads(10);
        var wireMock = new WireMockServer(options);
        wireMock.start();
        return wireMock;
    }

    @TestConfiguration
    static class WireMockRegistrarConfiguration {

        @Bean
        static WireMockBeansRegistrar wireMockBeansRegistrar() {
            return new WireMockBeansRegistrar();
        }
    }

    static class WireMockBeansRegistrar implements BeanDefinitionRegistryPostProcessor {

        @Override
        @SneakyThrows
        public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory ignored) {
            // no-op
        }

        @Override
        @SneakyThrows
        public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) {
            WIREMOCK_SERVERS_BY_QUALIFIER.forEach((beanName, singleton) -> register(registry, beanName, singleton));
        }

        private static void register(@NonNull BeanDefinitionRegistry registry, String name, WireMockServer instance) {
            var beanDefinition = new GenericBeanDefinition();
            beanDefinition.setBeanClass(WireMockServer.class);
            beanDefinition.setInstanceSupplier(() -> instance);
            beanDefinition.setDestroyMethodName("stop");

            registry.registerBeanDefinition(name, beanDefinition);
        }
    }
}
