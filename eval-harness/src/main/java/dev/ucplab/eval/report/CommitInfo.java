package dev.ucplab.eval.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The exact commit a report was produced from (13.5): {@code git rev-parse HEAD}, the branch, and whether the working
 * tree had changes ({@code git status --porcelain}, untracked files ignored). If git cannot answer, {@code GITHUB_SHA},
 * then {@code unknown}.
 */
public record CommitInfo(String sha, String branch, boolean dirty, String source) {

    public static final String UNKNOWN = "unknown";

    public CommitInfo {
        Objects.requireNonNull(sha, "sha");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(source, "source");
    }

    public boolean known() {
        return !sha.equals(UNKNOWN);
    }

    /** Reads the commit of the repository at {@code dir}. */
    public static CommitInfo detect(Path dir) {
        Optional<String> sha = git(dir, "rev-parse", "HEAD");
        if (sha.isPresent() && sha.get().matches("[0-9a-f]{40}")) {
            String branch = git(dir, "rev-parse", "--abbrev-ref", "HEAD").orElse(UNKNOWN);
            boolean dirty = git(dir, "status", "--porcelain", "--untracked-files=no").map(s -> !s.isBlank())
                    .orElse(true);
            return new CommitInfo(sha.get(), branch, dirty, "git");
        }
        String env = System.getenv("GITHUB_SHA");
        if (env != null && env.matches("[0-9a-f]{40}")) {
            return new CommitInfo(env, Optional.ofNullable(System.getenv("GITHUB_REF_NAME")).orElse(UNKNOWN), false,
                    "GITHUB_SHA");
        }
        return new CommitInfo(UNKNOWN, UNKNOWN, false, "none");
    }

    private static Optional<String> git(Path dir, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            return p.exitValue() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
