package se.rocketscien.harness.config;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@NoArgsConstructor(access = AccessLevel.PACKAGE)
class PreventTestMethodsInheritanceExtension implements BeforeEachCallback {

    private static final Map<Method, Class<?>> executedMethods = new HashMap<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        Method testMethod = context.getTestMethod().orElseThrow();
        Class<?> testClass = context.getTestClass().orElseThrow();

        Class<?> executedTestClass = executedMethods.putIfAbsent(testMethod, testClass);
        if (executedTestClass != null && !executedTestClass.equals(testClass)) {
            throw new IllegalCallerException(
                    """
                            Cannot execute test '%s' in test class '%s' because it was already executed in other test class '%s'.
                            Method declared in class '%s'."""
                            .formatted(
                                    testMethod.getName(),
                                    testClass.getSimpleName(),
                                    executedTestClass.getSimpleName(),
                                    testMethod.getDeclaringClass().getSimpleName()
                            )
            );
        }
    }
}
