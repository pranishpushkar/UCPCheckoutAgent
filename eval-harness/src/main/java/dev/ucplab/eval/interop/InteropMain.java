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
        Set<String> wanted = Set.of(System.getProperty("interop.rows", "S0,S1,S2,S3,S4,S5,S6,S7,S8,S9,S13,S14").split(","));
        Path work = repo.resolve("eval-harness/target/interop-work");
        if (Files.exists(work)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(work)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(work);
        List<InteropRows.Row> rows = InteropRows.rows(null).stream().filter(r -> wanted.contains(r.id())).toList();
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
                InteropRows.Result r = InteropRows.run(InteropRows.rows(null).get(0), 0, sample, proxy, profile, laneWork, scale, agent);
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
        // The differential report (H1 C9b): the lab and the sample run the same rows, the agent's transport is recorded, and every
        // raw wire difference must be explained by an entry of docs/interop/differences.md.
        List<WireDiff.Diff> wireDiffs = new ArrayList<>();
        Path wireWork = Files.createDirectories(work.resolve("wire"));
        try (dev.ucplab.merchant.LabMerchant lab = dev.ucplab.merchant.LabMerchant.start();
             ProfileServer profile = ProfileServer.start();
             SampleMerchant sample = SampleMerchant.start(new PinnedSampleLauncher(repo, wireWork.resolve("logs")), wireWork,
                     Duration.ofSeconds(60))) {
            var labTarget = new WireRows.Target("lab", lab.baseUrl(), dev.ucplab.eval.MerchantWiring.LAB,
                    List.of(new dev.ucplab.core.intent.ProposedLine("item_123", 1)), "tok_ok", "tok_decline",
                    () -> 2500L, p -> lab.setPrice("item_123", p), () -> 100, n -> lab.setStock("item_123", n));
            var sampleTarget = new WireRows.Target("sample", sample.baseUrl(), SampleWiring.of(profile.ucpAgent()), InteropRows.BASKET,
                    SampleWiring.SUCCESS_TOKEN, SampleWiring.FAIL_TOKEN, () -> sample.db().price(InteropRows.ITEM),
                    p -> sample.setPrice(InteropRows.ITEM, p), () -> sample.db().stock(InteropRows.ITEM),
                    n -> sample.setStock(InteropRows.ITEM, n));
            Path wireDir = repo.resolve("docs/interop/wire");
            for (String row : WireRows.ROWS) {
                List<WireStep> labSteps = WireRows.record(row, labTarget, wireWork, scale);
                List<WireStep> sampleSteps = WireRows.record(row, sampleTarget, wireWork, scale);
                WireFiles.write(wireDir, "lab", row, labSteps);
                WireFiles.write(wireDir, "sample", row, sampleSteps);
                wireDiffs.addAll(WireDiff.diff(row, labSteps, sampleSteps));
            }
        }
        var classified = DifferenceReport.parse(Files.readString(repo.resolve("docs/interop/differences.md")));
        var evaluation = DifferenceReport.evaluate(wireDiffs, classified);
        List<String> diffViolations = evaluation.violations();
        List<DifferenceReport.RowResult> rowResults = new ArrayList<>();
        for (InteropRows.Row row : rows) {
            List<InteropRows.Result> mine = all.stream().filter(r -> r.row().equals(row.id())).toList();
            var states = new java.util.TreeMap<String, Integer>();
            mine.forEach(r -> states.merge(r.state().name(), 1, Integer::sum));
            String agreement = row.id().equals("S5") ? "differs: no re-quote (complete-charges-held-quote)"
                    : row.id().equals("S6") || row.id().equals("S7") ? "agrees (refusal shape differs: refusal-4xx-at-complete)" : "agrees";
            rowResults.add(new DifferenceReport.RowResult(row.id(), mine.size(), states,
                    mine.stream().mapToInt(InteropRows.Result::newOrders).sum(),
                    mine.stream().mapToInt(r -> r.violations().size()).sum(), mine.stream().mapToLong(InteropRows.Result::millis).sum() / 1000.0,
                    agreement));
        }
        diffViolations.forEach(v -> report.append("DIFFERENCE ").append(v).append('\n'));
        failed |= !diffViolations.isEmpty();
        if (rows.size() == InteropRows.rows(null).size() && runs >= 20) {
            Path results = repo.resolve("docs/results");
            Files.createDirectories(results);
            Files.writeString(results.resolve("h1-interop-report.md"), DifferenceReport.markdown(rowResults, classified, wireDiffs, evaluation, findings));
            Files.writeString(results.resolve("h1-interop-report.json"), DifferenceReport.json(rowResults, wireDiffs, evaluation) + "\n");
        }
        System.out.print(report);
        Files.writeString(work.resolve("interop-report.txt"), report.toString());
        if (failed) {
            System.exit(1);
        }
    }
}
