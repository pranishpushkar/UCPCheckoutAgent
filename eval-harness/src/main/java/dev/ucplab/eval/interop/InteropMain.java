package dev.ucplab.eval.interop;

import dev.ucplab.eval.TimeScale;
import dev.ucplab.faultproxy.FaultProxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the H1 interop rows against the pinned sample merchant (H1 C8). Started only by the {@code interop} profile of
 * this module (never by {@code verify}), after {@code scripts/interop-fetch.sh}. Third-party code: it starts the sample from
 * the clone in {@code eval-harness/target/interop}, bound to 127.0.0.1, one per lane. Options (system properties):
 * {@code interop.runs} (per row, default 20), {@code interop.lanes} (default 4), {@code interop.rows} (comma list, default
 * all), {@code interop.k} (time scale, default 4). Exit status 1 if any run broke a row.
 */
public final class InteropMain {

    private InteropMain() {
    }

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(System.getProperty("interop.repo", "..")).toAbsolutePath().normalize();
        int runs = Integer.getInteger("interop.runs", 20);
        int lanes = Integer.getInteger("interop.lanes", 4);
        TimeScale scale = TimeScale.of(Integer.getInteger("interop.k", 4));
        Set<String> wanted = Set.of(System.getProperty("interop.rows", "S0,S1,S2,S3,S4,S5,S6,S7").split(","));
        Path work = repo.resolve("eval-harness/target/interop-work");
        if (Files.exists(work)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(work)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(work);
        List<InteropRows.Row> rows = InteropRows.rows("").stream().filter(r -> wanted.contains(r.id())).toList();
        long started = System.nanoTime();
        List<InteropRows.Result> all = new ArrayList<>();
        int[] fetches = new int[lanes];
        ExecutorService pool = Executors.newFixedThreadPool(lanes);
        List<Future<List<InteropRows.Result>>> futures = new ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            int index = lane;
            futures.add(pool.submit(() -> {
                List<InteropRows.Result> out = new ArrayList<>();
                Path laneWork = Files.createDirectories(work.resolve("lane-" + index));
                try (ProfileServer profile = ProfileServer.start();
                     SampleMerchant sample = SampleMerchant.start(new PinnedSampleLauncher(repo, laneWork.resolve("logs")),
                             laneWork, Duration.ofSeconds(60));
                     FaultProxy proxy = FaultProxy.start(sample.baseUrl(), 0, InteropRows.key())) {
                    for (InteropRows.Row row : rows) {
                        for (int run = index; run < runs; run += lanes) {
                            out.add(InteropRows.run(row, run, sample, proxy, profile, laneWork, scale));
                        }
                    }
                    fetches[index] = profile.fetches();
                }
                return out;
            }));
        }
        try {
            for (Future<List<InteropRows.Result>> f : futures) {
                all.addAll(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        long total = (System.nanoTime() - started) / 1_000_000;
        List<String> findings = new ArrayList<>();
        if (Boolean.parseBoolean(System.getProperty("interop.probes", "true"))) {
            Path laneWork = Files.createDirectories(work.resolve("probes"));
            try (ProfileServer profile = ProfileServer.start();
                 SampleMerchant sample = SampleMerchant.start(new PinnedSampleLauncher(repo, laneWork.resolve("logs")), laneWork,
                         Duration.ofSeconds(60))) {
                findings.addAll(new SampleProbes(sample, profile.ucpAgent()).run());
            }
            // the unreachable profile: the agent-profile server is stopped, so the sample cannot fetch the URL it is given
            try (ProfileServer profile = ProfileServer.start();
                 SampleMerchant sample = SampleMerchant.start(new PinnedSampleLauncher(repo, laneWork.resolve("logs2")), laneWork,
                         Duration.ofSeconds(60));
                 FaultProxy proxy = FaultProxy.start(sample.baseUrl(), 0, InteropRows.key())) {
                String agent = profile.ucpAgent();
                profile.close();
                InteropRows.Result r = InteropRows.run(InteropRows.rows("").get(0), 0, sample, proxy, profile, laneWork, scale, agent);
                findings.add("unreachable agent profile URL: the agent's run ended " + r.state() + ", new orders " + r.newOrders()
                        + ", violations " + r.violations());
            }
        }
        StringBuilder report = new StringBuilder();
        boolean failed = false;
        report.append(String.format("%-4s %-5s %-60s %s%n", "row", "runs", "terminal states", "orders / violations / time"));
        for (InteropRows.Row row : rows) {
            List<InteropRows.Result> mine = all.stream().filter(r -> r.row().equals(row.id())).toList();
            var states = new java.util.TreeMap<String, Integer>();
            mine.forEach(r -> states.merge(r.state().name(), 1, Integer::sum));
            long orders = mine.stream().mapToInt(InteropRows.Result::newOrders).sum();
            List<String> violations = mine.stream().flatMap(r -> r.violations().stream().map(v -> "run " + r.run() + ": " + v)).toList();
            long ms = mine.stream().mapToLong(InteropRows.Result::millis).sum();
            failed |= !violations.isEmpty();
            report.append(String.format("%-4s %-5d %-60s orders=%d violations=%d time=%.1fs (sum of runs)%n", row.id(),
                    mine.size(), states, orders, violations.size(), ms / 1000.0));
            violations.stream().limit(5).forEach(v -> report.append("       ").append(v).append('\n'));
        }
        report.append(String.format("lanes=%d runs/row=%d k=%d total=%.1fs; agent-profile fetches by the sample per lane: %s%n",
                lanes, runs, scale.k(), total / 1000.0, java.util.Arrays.toString(fetches)));
        findings.forEach(f -> report.append("PROBE ").append(f).append('\n'));
        System.out.print(report);
        Files.writeString(work.resolve("interop-report.txt"), report.toString());
        if (failed) {
            System.exit(1);
        }
    }
}
