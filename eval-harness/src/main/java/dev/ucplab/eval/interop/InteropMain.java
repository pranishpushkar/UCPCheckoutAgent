package dev.ucplab.eval.interop;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs the H1 interop rows against the pinned sample merchant (H1 C8). Started only by the {@code interop} profile of
 * this module (never by {@code verify}), after {@code scripts/interop-fetch.sh}. Third-party code: it starts the sample from
 * the clone in {@code eval-harness/target/interop}, bound to 127.0.0.1.
 */
public final class InteropMain {

    private InteropMain() {
    }

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(System.getProperty("interop.repo", "..")).toAbsolutePath().normalize();
        Path work = repo.resolve("eval-harness/target/interop-work");
        try (ProfileServer profile = ProfileServer.start();
             SampleMerchant sample = SampleMerchant.start(new PinnedSampleLauncher(repo, work.resolve("logs")), work,
                     Duration.ofSeconds(60))) {
            System.out.println("sample at " + sample.baseUrl() + ", orders " + sample.orders().size() + ", agent profile "
                    + profile.ucpAgent());
        }
    }
}
