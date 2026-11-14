package dev.ucplab.eval.interop;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The harness's hooks on the sample merchant's two SQLite files (H1 plan sections 4 and 5): the merchant's own record of
 * its orders (read only), and the price and stock changes a row makes. It refuses a database whose tables differ from the
 * shape pinned in docs/interop/spike.md. Never logs a payment token: it reads none.
 */
public final class SampleDb {

    private final Path productsDb;
    private final Path transactionsDb;
    private Path template;

    public SampleDb(Path productsDb, Path transactionsDb) {
        this.productsDb = Objects.requireNonNull(productsDb, "productsDb");
        this.transactionsDb = Objects.requireNonNull(transactionsDb, "transactionsDb");
        try {
            requireColumns(transactionsDb, "orders", Set.of("id", "data"), true);
            requireColumns(transactionsDb, "inventory", Set.of("product_id", "quantity"), false);
            requireColumns(productsDb, "products", Set.of("id", "price"), false);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the sample's schema: " + e.getMessage(), e);
        }
    }

    /** {@code exact}: the table has exactly these columns; else it has at least them. */
    private static void requireColumns(Path file, String table, Set<String> expected, boolean exact) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (r.next()) {
                actual.add(r.getString("name"));
            }
        }
        if (actual.isEmpty() || !actual.containsAll(expected) || (exact && !actual.equals(expected))) {
            throw new IllegalStateException("the sample's " + table + " table is not the pinned shape " + expected
                    + " (found " + actual + "): the pin in docs/interop/pin.txt and this check must change together");
        }
    }

    /** A read-only connection to the transactions file: {@code PRAGMA query_only}, so no statement through it can write. */
    public Connection readOnlyConnection() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + transactionsDb);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA query_only = ON");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /** The merchant's own record of the orders placed, oldest first. */
    public List<SampleOrder> orders() {
        List<SampleOrder> out = new ArrayList<>();
        try (Connection c = readOnlyConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select id, data from orders order by rowid")) {
            while (r.next()) {
                Map<String, Object> data = LenientJson.readObject(r.getString("data"));
                out.add(new SampleOrder(r.getString("id"), String.valueOf(data.get("checkout_id")), total(data)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the sample's orders: " + e.getMessage(), e);
        }
        return out;
    }

    private static long total(Map<String, Object> data) {
        if (data.get("totals") instanceof List<?> totals) {
            for (Object o : totals) {
                if (o instanceof Map<?, ?> t && "total".equals(t.get("type")) && t.get("amount") instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return -1;
    }

    /** {@code UPDATE products SET price = ? WHERE id = ?}: the merchant reads it at its next checkout operation. */
    public void setPrice(String itemId, long priceMinor) {
        update(productsDb, "update products set price = ? where id = ?", priceMinor, itemId);
    }

    /** {@code UPDATE inventory SET quantity = ? WHERE product_id = ?}. */
    public void setStock(String itemId, int quantity) {
        update(transactionsDb, "update inventory set quantity = ? where product_id = ?", (long) quantity, itemId);
    }

    public long price(String itemId) {
        return queryLong(productsDb, "select price from products where id = ?", itemId);
    }

    public int stock(String itemId) {
        return (int) queryLong(transactionsDb, "select quantity from inventory where product_id = ?", itemId);
    }

    private static void update(Path file, String sql, long value, String key) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, value);
            s.setString(2, key);
            if (s.executeUpdate() != 1) {
                throw new IllegalArgumentException("no such item in the sample: " + key);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot change the sample's data: " + e.getMessage(), e);
        }
    }

    private static long queryLong(Path file, String sql, String key) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, key);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalArgumentException("no such item in the sample: " + key);
                }
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the sample's data: " + e.getMessage(), e);
        }
    }

    /** Copies both files into {@code folder}, the state {@link #reset()} returns to. Call it before the first run. */
    public void snapshotTemplate(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.copy(productsDb, folder.resolve("products.db"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(transactionsDb, folder.resolve("transactions.db"), StandardCopyOption.REPLACE_EXISTING);
        template = folder;
    }

    /** Puts both files back as {@link #snapshotTemplate} found them. The merchant must not be running. */
    public void reset() throws IOException {
        if (template == null) {
            throw new IllegalStateException("no template: snapshotTemplate was not called");
        }
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(productsDb + suffix));
            Files.deleteIfExists(Path.of(transactionsDb + suffix));
        }
        Files.copy(template.resolve("products.db"), productsDb);
        Files.copy(template.resolve("transactions.db"), transactionsDb);
    }
}
