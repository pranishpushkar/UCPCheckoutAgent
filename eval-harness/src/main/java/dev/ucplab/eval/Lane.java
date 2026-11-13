package dev.ucplab.eval;

import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.merchant.LabMerchant;
import dev.ucplab.persistence.Migrations;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One lane of the evaluation (m4-plan section 4): its own in-process merchant on its own port, its own fault proxy in
 * front of it, and its own work folder with a template database migrated once, so runs in different lanes share
 * nothing. Runs inside a lane are sequential, and each gets a fresh copy of the template. The proxy gets the
 * evaluation's {@code bodyHmac} key at start-up (§12 Body hash), never through its admin API.
 */
public final class Lane implements AutoCloseable {

    private final int index;
    private final LabMerchant merchant;
    private final FaultProxy proxy;
    private final GroundTruth groundTruth;
    private final Path workDir;
    private final Path template;
    private final AtomicInteger databases = new AtomicInteger();
    private final AtomicLong logMark = new AtomicLong();

    private Lane(int index, LabMerchant merchant, FaultProxy proxy, Path workDir, Path template) {
        this.index = index;
        this.merchant = merchant;
        this.proxy = proxy;
        this.groundTruth = new GroundTruth(merchant);
        this.workDir = workDir;
        this.template = template;
    }

    /**
     * Starts lane {@code index}: migrates its template database under {@code workDir}, starts its merchant, then its
     * proxy, handing the proxy {@code hmacKey} (32 bytes).
     */
    public static Lane start(int index, byte[] hmacKey, Path workDir) throws IOException {
        Objects.requireNonNull(hmacKey, "hmacKey");
        Objects.requireNonNull(workDir, "workDir");
        if (index < 0) {
            throw new IllegalArgumentException("lane index must not be negative: " + index);
        }
        Path dir = Files.createDirectories(workDir.resolve("lane-" + index));
        Path template = dir.resolve("template.db");
        Migrations.migrate(template);
        LabMerchant merchant = LabMerchant.start();
        try {
            return new Lane(index, merchant, FaultProxy.start(merchant.baseUrl(), 0, hmacKey), dir, template);
        } catch (IOException | RuntimeException e) {
            merchant.close();
            throw e;
        }
    }

    public int index() {
        return index;
    }

    /** Ground-truth reads and lab controls, straight to the merchant. */
    public LabMerchant merchant() {
        return merchant;
    }

    public GroundTruth groundTruth() {
        return groundTruth;
    }

    public FaultProxy proxy() {
        return proxy;
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
        List<LogEntry> newer = proxy.log(logMark.get());
        if (!newer.isEmpty()) {
            logMark.set(newer.getLast().seq());
        }
        return logMark.get();
    }

    /** Where the agent under test is pointed: the proxy, never the merchant itself. */
    public URI agentFacingUrl() {
        return proxy.baseUrl();
    }

    @Override
    public void close() {
        proxy.close();
        merchant.close();
    }
}
