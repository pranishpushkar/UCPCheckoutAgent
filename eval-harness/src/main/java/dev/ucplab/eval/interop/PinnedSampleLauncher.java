package dev.ucplab.eval.interop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the pinned sample's own scripts (H1 C1): {@code import_csv.py} for the databases and, through
 * {@code scripts/interop_serve.py}, the server bound to 127.0.0.1. Third-party code: it runs only from the clone that
 * {@code scripts/interop-fetch.sh} makes, under {@code -Pinterop}, never inside {@code ./mvnw verify}. The environment it
 * starts with has no model key.
 */
public final class PinnedSampleLauncher implements SampleMerchant.Launcher {

    private final Path server;
    private final Path data;
    private final Path serveScript;
    private final Path logs;

    /** @param repo the repository root; the clone is {@code eval-harness/target/interop/samples} under it */
    public PinnedSampleLauncher(Path repo, Path logs) {
        Path samples = repo.resolve("eval-harness/target/interop/samples");
        this.server = samples.resolve("rest/python/server");
        this.data = samples.resolve("rest/python/test_data/flower_shop");
        this.serveScript = repo.resolve("scripts/interop_serve.py");
        this.logs = logs;
        if (!java.nio.file.Files.exists(server.resolve("uv.lock"))) {
            throw new IllegalStateException("the pinned sample is not fetched: run scripts/interop-fetch.sh first");
        }
    }

    private ProcessBuilder builder(List<String> command, String log) {
        ProcessBuilder b = new ProcessBuilder(command).directory(server.toFile());
        b.environment().remove("GEMINI_API_KEY");
        b.redirectErrorStream(true).redirectOutput(logs.resolve(log).toFile());
        return b;
    }

    @Override
    public void importData(Path productsDb, Path transactionsDb) throws IOException {
        java.nio.file.Files.createDirectories(logs);
        Process p = builder(List.of("uv", "run", "--frozen", "import_csv.py", "--products_db_path=" + productsDb,
                "--transactions_db_path=" + transactionsDb, "--data_dir=" + data), "import-" + productsDb.getParent().getFileName() + ".log").start();
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("import_csv.py failed; see " + logs);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @Override
    public SampleMerchant.Server serve(Path productsDb, Path transactionsDb, int port, String secret) throws IOException {
        java.nio.file.Files.createDirectories(logs);
        Process p = builder(List.of("uv", "run", "--frozen", "python", serveScript.toString(), productsDb.toString(),
                transactionsDb.toString(), Integer.toString(port), secret), "serve-" + port + ".log").start();
        return new SampleMerchant.Server() {
            @Override
            public boolean alive() {
                return p.isAlive();
            }

            @Override
            public String describe() {
                return p.isAlive() ? "running" : "exit code " + p.exitValue() + ", see " + logs;
            }

            @Override
            public void close() {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
                try {
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    p.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        };
    }
}
