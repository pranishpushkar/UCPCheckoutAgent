package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * C1 (m6-plan section 2.1): the versions agent-service runs with, read from the jars on its classpath. Spring Boot's
 * BOM is imported here and nowhere else; it must not move the versions the other modules were tested with.
 */
class ClasspathVersionsTest {

    // Covers: 5.2, R2 (genai runs on the Jackson 2 that agent-adk's tests use, not the BOM's 2.21)
    @Test
    void classpath_jackson2_isGenaisVersion() {
        assertThat(mavenVersion("com.fasterxml.jackson.core", "jackson-databind")).isEqualTo("2.18.11");
        assertThat(mavenVersion("com.fasterxml.jackson.core", "jackson-core")).isEqualTo("2.18.11");
        assertThat(mavenVersion("com.fasterxml.jackson.datatype", "jackson-datatype-jdk8")).isEqualTo("2.18.11");
        assertThat(mavenVersion("com.fasterxml.jackson.datatype", "jackson-datatype-jsr310")).isEqualTo("2.18.11");
    }

    // Covers: 5.2 (jackson-annotations is shared: Jackson 3.1 keeps using the 2.x artifact and needs 2.21, which is
    // why it is the one Jackson 2 module not pinned to genai's 2.18.11; LiveProviderClasspathTest runs genai on it)
    @Test
    void classpath_jacksonAnnotations_isWhatJackson3Needs() {
        assertThat(mavenVersion("com.fasterxml.jackson.core", "jackson-annotations")).startsWith("2.21");
    }

    // Covers: section 10, 15 (the parent pom's Flyway and sqlite-jdbc, not the BOM's 12.4.0 and 3.53.4.0)
    @Test
    void classpath_flywayAndSqlite_areTheParentsPins() {
        assertThat(mavenVersion("org.flywaydb", "flyway-core")).isEqualTo("13.12.1");
        assertThat(mavenVersion("org.xerial", "sqlite-jdbc")).isEqualTo("3.54.1.0");
    }

    // Covers: section 15 (the parent's junit-bom, not the BOM's Jupiter 6.0.3)
    @Test
    void classpath_jupiter_isTheParentsPin() {
        assertThat(Test.class.getPackage().getImplementationVersion()).isEqualTo("6.1.3");
    }

    // Covers: section 11 (one logging backend: exactly one SLF4J provider)
    @Test
    void classpath_slf4j_hasExactlyOneProvider() {
        List<String> providers = ServiceLoader.load(SLF4JServiceProvider.class).stream()
                .map(p -> p.type().getName()).toList();

        assertThat(providers).singleElement().asString().startsWith("ch.qos.logback.");
    }

    private static String mavenVersion(String group, String artifact) {
        String path = "META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        try (InputStream in = ClasspathVersionsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (IOException e) {
            throw new AssertionError(path, e);
        }
    }
}
