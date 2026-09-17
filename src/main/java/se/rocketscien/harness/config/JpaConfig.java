package se.rocketscien.harness.config;

import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единая точка настройки JPA/Hibernate. jsonb-колонки маппятся Jackson 3 (D-M1-2):
 * Boot 4 = Jackson 3, но в classpath транзитивно присутствует Jackson 2 (Spring AI → openai-java-core),
 * а hibernate-core содержит оба маппера — без явного пина {@code hibernate.type.json_format_mapper}
 * Hibernate выбрал бы Jackson 2.
 */
@Configuration(proxyBeanMethods = false)
public class JpaConfig {

    @Bean
    public HibernatePropertiesCustomizer hibernateJsonFormatMapperCustomizer() {
        return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER, new Jackson3JsonFormatMapper());
    }
}
