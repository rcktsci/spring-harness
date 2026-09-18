package se.rocketscien.harness.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import se.rocketscien.harness.api.MergePatchHttpMessageConverter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * MVC-конфигурация HTTP-слоя: converter merge-patch (спека PATCH /sessions —
 * {@code application/merge-patch+json}); сериализация остального — Jackson 3 (Boot 4).
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.addFirst(new MergePatchHttpMessageConverter(objectMapper));
    }
}
