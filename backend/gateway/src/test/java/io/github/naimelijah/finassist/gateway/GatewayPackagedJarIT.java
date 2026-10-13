package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * The gateway as {@code package} built it: the repackaged jar that {@code backend/gateway/Dockerfile}
 * copies into the image and runs with {@code java -jar}.
 *
 * <p>Every other suite runs on Maven's test classpath, which can hold a library the packaged jar
 * lacks. That is how #233 shipped. snakeyaml, declared at test scope, left the jar, so Boot could
 * not read {@code application.yml} and the gateway could not start anywhere outside a test, while
 * every suite stayed green. Failsafe runs after {@code package}, so the jar exists by the time this
 * does; its path comes from the pom, as {@value #JAR_PROPERTY}.
 */
class GatewayPackagedJarIT {

    private static final String JAR_PROPERTY = "finassist.gateway.jar";

    /** Where Spring Boot's executable-jar layout keeps each runtime dependency. */
    private static final String LIBRARIES = "BOOT-INF/lib/";

    /** Boot's {@code YamlPropertySourceLoader} reads {@code application.yml} with it. */
    private static final String YAML_PARSER = LIBRARIES + "snakeyaml-";

    @Test
    void Given_the_packaged_jar_When_its_libraries_are_listed_Then_it_carries_the_yaml_parser() throws IOException {
        Path jar = packagedJar();
        assertThat(jar).isRegularFile();
        try (JarFile packaged = new JarFile(jar.toFile())) {
            assertThat(packaged.stream().map(JarEntry::getName).filter(name -> name.startsWith(LIBRARIES)))
                    .as("the libraries packaged in %s", jar.getFileName())
                    .anyMatch(name -> name.startsWith(YAML_PARSER));
        }
    }

    /** Fails loudly rather than opening nothing when the build did not say where the jar is. */
    private static Path packagedJar() {
        String jar = System.getProperty(JAR_PROPERTY);
        if (jar == null) {
            throw new IllegalStateException(JAR_PROPERTY
                    + " is not set. Failsafe sets it in pom.xml; run this test through `./mvnw verify`,"
                    + " after `package`, or pass -D" + JAR_PROPERTY + "=<path to the repackaged jar>.");
        }
        return Path.of(jar);
    }
}
