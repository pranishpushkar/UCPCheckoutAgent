package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The child-JVM spike (m4-plan risk 1): from inside surefire, start a second JVM with this JVM's own java binary and
 * classpath, and read what it prints. S9 and S28 kill such a child with kill -9, so this must work on macOS and Linux.
 */
class ChildJvmTest {

    // Covers: S9, S28, NFR4 (the harness can start an agent child JVM from a test run, with every module on its path)
    @Test
    void childJvm_startsWithTheParentClasspath_andPrintsReady() throws Exception {
        String javaBin = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("java.class.path");
        System.out.println("spike: java=" + javaBin + " classpath entries=" + classpath.split(File.pathSeparator).length
                + " first=" + Path.of(classpath.split(File.pathSeparator)[0]).getFileName());

        Process child = new ProcessBuilder(List.of(javaBin, "-cp", classpath, ChildProbe.class.getName(), "spike"))
                .redirectErrorStream(true).start();
        boolean exited = child.waitFor(30, TimeUnit.SECONDS);
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(exited).isTrue();
        assertThat(out).contains("READY spike");
        assertThat(child.exitValue()).isZero();
    }
}
