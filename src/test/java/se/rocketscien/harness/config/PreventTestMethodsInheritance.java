package se.rocketscien.harness.config;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Защита от дублирования прогонов тест-методов при наследовании базовых классов (по
 * корпоративному навыку): повторное исполнение метода в другом классе валится с
 * {@link IllegalCallerException}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(PreventTestMethodsInheritanceExtension.class)
public @interface PreventTestMethodsInheritance {
}
