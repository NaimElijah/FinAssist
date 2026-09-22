package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Guards the whole class of defect behind the regression that left {@code
 * LedgerSecurityConfiguration.jwtDecoder} reading {@code
 * ${finassist.security.jwk-set-uri}} while {@code application.yml} defines only {@code
 * finassist.security.jwks-uri}: a mismatch no {@code *IT} caught, because every one runs through
 * {@code PostgresFixture}, which supplied the broken key itself through {@code
 * @DynamicPropertySource} and so never exercised {@code application.yml}'s real binding.
 *
 * <p>This resolves every {@code @Value} placeholder the ledger's production code declares against
 * the shipped {@code application.yml} alone, with no context started and no Docker needed. {@link
 * ClassFileImporter} reads bytecode rather than loading classes, the same static approach {@code
 * LedgerArchitectureTest} already uses for the ledger's other structural rules.
 */
class ApplicationYmlPlaceholdersTest {

    private static final String ROOT = "io.github.naimelijah.finassist.ledger";

    /**
     * Every {@code @Value} placeholder expression declared by the ledger's production code, found
     * without starting Spring. Spring honours {@code @Value} in three places, and every one is
     * covered: a field, a constructor parameter, and — how every current usage in this codebase is
     * actually written — a {@code @Bean} factory method's parameter.
     */
    private static List<String> productionValuePlaceholders() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT);

        List<String> placeholders = new ArrayList<>();
        for (JavaClass clazz : production) {
            for (JavaField field : clazz.getFields()) {
                if (field.isAnnotatedWith(Value.class)) {
                    placeholders.add(field.getAnnotationOfType(Value.class).value());
                }
            }
            for (JavaConstructor constructor : clazz.getConstructors()) {
                addAnnotatedParameters(constructor.getParameters(), placeholders);
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (method.isAnnotatedWith(Bean.class)) {
                    addAnnotatedParameters(method.getParameters(), placeholders);
                }
            }
        }
        return placeholders;
    }

    private static void addAnnotatedParameters(List<JavaParameter> parameters, List<String> placeholders) {
        for (JavaParameter parameter : parameters) {
            if (parameter.isAnnotatedWith(Value.class)) {
                placeholders.add(parameter.getAnnotationOfType(Value.class).value());
            }
        }
    }

    /**
     * A scan that finds nothing proves nothing — the same reason the repo deleted {@code
     * archRule.failOnEmptyShould=false}. If this ever goes red on an empty list, the scan itself
     * broke; it is not evidence that every placeholder now resolves.
     */
    @Test
    void Given_the_ledgers_production_code_When_scanned_for_at_Value_placeholders_Then_at_least_one_is_found() {
        assertThat(productionValuePlaceholders()).isNotEmpty();
    }

    /**
     * Loads exactly what ships, with {@code systemProperties} and {@code systemEnvironment}
     * removed so no developer's or CI's own environment can satisfy a placeholder {@code
     * application.yml} does not — the same condition that let 26505db's regression through every
     * {@code *IT}, none of which boots on this binding alone.
     */
    @Test
    void
            Given_application_yml_as_the_only_property_source_When_resolving_every_at_Value_placeholder_Then_every_one_resolves()
                    throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        Resource applicationYml = new ClassPathResource("application.yml");
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("application.yml", applicationYml);
        loaded.forEach(environment.getPropertySources()::addLast);

        List<String> unresolvable = new ArrayList<>();
        for (String placeholder : productionValuePlaceholders()) {
            try {
                environment.resolveRequiredPlaceholders(placeholder);
            } catch (IllegalArgumentException e) {
                unresolvable.add(placeholder + ": " + e.getMessage());
            }
        }

        assertThat(unresolvable).isEmpty();
    }
}
