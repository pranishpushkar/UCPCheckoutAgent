package dev.ucplab.eval;

import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.merchant.LabMerchant;
import dev.ucplab.persistence.Migrations;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One lane of the evaluation (m4-plan section 4): its own in-process merchant on its own port, its own fault proxy in
 * front of it, and its own work folder with a template database migrated once, so runs in different lanes share
 * nothing. Runs inside a lane are sequential, and each gets a fresh copy of the template. The proxy gets the
 * evaluation's {@code bodyHmac} key at start-up (§12 Body hash), never through its admin API.
 *
 * <p>A lane of one (every row before M6a) is exactly that. A lane of {@value #MAX_MERCHANTS} or fewer starts that many
 * merchants, each with a proxy of its own (m6a-plan section 11): merchant 0 serves the built-in catalog, as
 * {@code merchant-a} does in Compose, and merchants 1 and 2 the files {@code dev/catalogs/b.json} and {@code c.json}.
 * They share nothing: no catalog, no order or session counter, no proxy log. The accessors without an index are
 * merchant 0's.
 */
public final class Lane implements AutoCloseable {

    /** The most merchants in one lane: the built-in catalog and the two files of {@code dev/catalogs}. */
    public static final int MAX_MERCHANTS = 3;
    private static final List<String> CATALOG_FILES = List.of("b.json", "c.json");

    private final int index;
    private final List<LabMerchant> merchants;
    private final List<FaultProxy> proxies;
    private final List<GroundTruth> groundTruths;
    private final Path workDir;
    private final Path template;
    private final AtomicInteger databases = new AtomicInteger();
    private final List<AtomicLong> logMarks;

    private Lane(int index, List<LabMerchant> merchants, List<FaultProxy> proxies, Path workDir, Path template) {
        this.index = index;
        this.merchants = List.copyOf(merchants);
        this.proxies = List.copyOf(proxies);
        this.groundTruths = merchants.stream().map(GroundTruth::new).toList();
        this.workDir = workDir;
        this.template = template;
        this.logMarks = merchants.stream().map(m -> new AtomicLong()).toList();
    }

    /**
     * Starts lane {@code index}: migrates its template database under {@code workDir}, starts its merchant, then its
     * proxy, handing the proxy {@code hmacKey} (32 bytes).
     */
    public static Lane start(int index, byte[] hmacKey, Path workDir) throws IOException {
        return start(index, hmacKey, workDir, 1);
    }

    /** As {@link #start(int, byte[], Path)}, with {@code merchantCount} merchants (1 to {@value #MAX_MERCHANTS}). */
    public static Lane start(int index, byte[] hmacKey, Path workDir, int merchantCount) throws IOException {
        Objects.requireNonNull(hmacKey, "hmacKey");
        Objects.requireNonNull(workDir, "workDir");
        if (index < 0) {
            throw new IllegalArgumentException("lane index must not be negative: " + index);
        }
        if (merchantCount < 1 || merchantCount > MAX_MERCHANTS) {
            throw new IllegalArgumentException("a lane has 1 to " + MAX_MERCHANTS + " merchants: " + merchantCount);
        }
        Path dir = Files.createDirectories(workDir.resolve("lane-" + index));
        Path template = dir.resolve("template.db");
        Migrations.migrate(template);
        List<LabMerchant> merchants = new ArrayList<>();
        List<FaultProxy> proxies = new ArrayList<>();
        try {
            for (int i = 0; i < merchantCount; i++) {
                LabMerchant merchant = i == 0 ? LabMerchant.start() : LabMerchant.start(catalogFile(CATALOG_FILES.get(i - 1)));
                merchants.add(merchant);
                proxies.add(FaultProxy.start(merchant.baseUrl(), 0, hmacKey));
            }
            return new Lane(index, merchants, proxies, dir, template);
        } catch (IOException | RuntimeException e) {
            proxies.forEach(FaultProxy::close);
            merchants.forEach(LabMerchant::close);
            throw e;
        }
    }

    /**
     * The catalog file the Compose stack uses, found from the working directory (the repository root under Maven's
     * {@code exec}, the module under Surefire): the one source, so a lane's merchants are the Compose merchants.
     */
    static Path catalogFile(String name) {
        Path here = Path.of("").toAbsolutePath();
        for (Path dir = here; dir != null; dir = dir.getParent()) {
            Path file = dir.resolve("dev").resolve("catalogs").resolve(name);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new IllegalStateException("dev/catalogs/" + name + " not found above " + here);
    }

    public int index() {
        return index;
    }

    /** How many merchants (and proxies) the lane has. */
    public int merchantCount() {
        return merchants.size();
    }

    /** The id each merchant has in the agent's registry, in lane order. */
    public List<MerchantId> merchantIds() {
        return List.of(Scenarios.SHOP, Scenarios.SHOP_B, Scenarios.SHOP_C).subList(0, merchants.size());
    }

    /** Ground-truth reads and lab controls, straight to the merchant. */
    public LabMerchant merchant() {
        return merchants.getFirst();
    }

    public LabMerchant merchant(int i) {
        return merchants.get(i);
    }

    public GroundTruth groundTruth() {
        return groundTruths.getFirst();
    }

    public GroundTruth groundTruth(int i) {
        return groundTruths.get(i);
    }

    public FaultProxy proxy() {
        return proxies.getFirst();
    }

    public FaultProxy proxy(int i) {
        return proxies.get(i);
    }

    /**
     * A fresh, migrated database file for one run: a copy of the lane's template (with its WAL and shared-memory files,
     * if SQLite left any), so no run sees another's rows and no run pays for migrations.
     */
    public Path newDatabase(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("not a database name: " + name);
        }
        Path file = workDir.resolve(name + "-" + databases.incrementAndGet() + ".db");
        Files.copy(template, file);
        for (String suffix : new String[] {"-wal", "-shm"}) {
            Path side = template.resolveSibling(template.getFileName() + suffix);
            if (Files.exists(side)) {
                Files.copy(side, file.resolveSibling(file.getFileName() + suffix));
            }
        }
        return file;
    }

    /** The highest {@code seq} in the proxy log so far: a run's own entries are the ones after its start mark. */
    public long proxyLogMark() {
        return proxyLogMark(0);
    }

    /** As {@link #proxyLogMark()}, for merchant {@code i}'s proxy. */
    public long proxyLogMark(int i) {
        AtomicLong mark = logMarks.get(i);
        List<LogEntry> newer = proxies.get(i).log(mark.get());
        if (!newer.isEmpty()) {
            mark.set(newer.getLast().seq());
        }
        return mark.get();
    }

    /** Where the agent under test is pointed: the proxy, never the merchant itself. */
    public URI agentFacingUrl() {
        return agentFacingUrl(0);
    }

    public URI agentFacingUrl(int i) {
        return proxies.get(i).baseUrl();
    }

    @Override
    public void close() {
        proxies.forEach(FaultProxy::close);
        merchants.forEach(LabMerchant::close);
    }
}
