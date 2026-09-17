package se.rocketscien.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties
@ConfigurationPropertiesScan
@SpringBootApplication(proxyBeanMethods = false)
class HarnessApplication {

    static void main() {
        SpringApplication.run(HarnessApplication.class);
    }
}
