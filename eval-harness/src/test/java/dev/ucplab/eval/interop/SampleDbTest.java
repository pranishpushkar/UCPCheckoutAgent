package dev.ucplab.eval.interop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The harness's hooks on the sample merchant's SQLite files (H1 C7), on fake files with the pinned shape: no Python, no
 * server. The sample's tables, as recorded in docs/interop/spike.md: {@code orders(id, data)} and {@code request_logs} in
 * the transactions file with {@code inventory(product_id, quantity)}, and {@code products(id, price, ...)} in the other.
 */
class SampleDbTest {

    @TempDir
    Path dir;

    static void create(Path products, Path tx, String ordersColumns) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + products); Statement s = c.createStatement()) {
            s.execute("create table products (id text primary key, title text, price integer)");
            s.execute("insert into products values ('bouquet_roses', 'Roses', 3500), ('pot_ceramic', 'Pot', 1200)");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tx); Statement s = c.createStatement()) {
            s.execute("create table orders (" + ordersColumns + ")");
            s.execute("create table inventory (product_id text primary key, quantity integer)");
            s.execute("create table request_logs (id integer primary key, data text)");
            s.execute("insert into inventory values ('bouquet_roses', 100), ('pot_ceramic', 50)");
        }
    }

    private SampleDb db() throws SQLException {
        Path p = dir.resolve("products.db");
        Path t = dir.resolve("transactions.db");
        create(p, t, "id text primary key, data json");
        return new SampleDb(p, t);
    }

    // Covers: R1, H1 plan section 4 (the schema check refuses to start when the orders table is not the pinned shape)
    @Test
    void aChangedOrdersTable_isRefused() throws SQLException {
        Path p = dir.resolve("products.db");
        Path t = dir.resolve("transactions.db");
        create(p, t, "id text primary key, payload json, extra text");

        assertThatThrownBy(() -> new SampleDb(p, t)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders").hasMessageContaining("pinned");
    }

    // Covers: I1, 13.1 (orders() reads the merchant's own record, and the connection it reads through cannot write)
    @Test
    void orders_reads_andItsConnectionCannotWrite() throws SQLException {
        SampleDb db = db();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("transactions.db")); Statement s = c.createStatement()) {
            s.execute("insert into orders values ('o1', '{\"checkout_id\":\"c1\",\"currency\":\"USD\",\"totals\":["
                    + "{\"type\":\"subtotal\",\"amount\":3500},{\"type\":\"total\",\"amount\":3500}]}')");
        }

        List<SampleOrder> orders = db.orders();

        assertThat(orders).singleElement().satisfies(o -> {
            assertThat(o.id()).isEqualTo("o1");
            assertThat(o.checkoutId()).isEqualTo("c1");
            assertThat(o.totalMinor()).isEqualTo(3500);
        });
        try (Connection read = db.readOnlyConnection(); Statement s = read.createStatement()) {
            assertThatThrownBy(() -> s.execute("delete from orders")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("insert into orders values ('o2', '{}')")).isInstanceOf(SQLException.class);
        }
        assertThat(db.orders()).hasSize(1);
    }

    // Covers: S5, S6, 13.1 (setPrice and setStock change exactly the row they name, and nothing else)
    @Test
    void setPriceAndSetStock_changeExactlyWhatTheyName() throws SQLException {
        SampleDb db = db();

        db.setPrice("bouquet_roses", 3700);
        db.setStock("pot_ceramic", 0);

        assertThat(db.price("bouquet_roses")).isEqualTo(3700);
        assertThat(db.price("pot_ceramic")).isEqualTo(1200);
        assertThat(db.stock("pot_ceramic")).isZero();
        assertThat(db.stock("bouquet_roses")).isEqualTo(100);
        assertThatThrownBy(() -> db.setPrice("no_such_item", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 13.1 (reset restores the files from the copies taken at start: orders, prices and stock are as they were)
    @Test
    void reset_restoresTheFreshFiles() throws Exception {
        SampleDb db = db();
        db.snapshotTemplate(dir.resolve("template"));
        db.setPrice("bouquet_roses", 1);
        db.setStock("bouquet_roses", 1);

        db.reset();

        assertThat(db.price("bouquet_roses")).isEqualTo(3500);
        assertThat(db.stock("bouquet_roses")).isEqualTo(100);
        assertThat(db.orders()).isEmpty();
    }
}
