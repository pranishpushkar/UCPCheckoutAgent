package dev.ucplab.service;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The I8 scan of the service (m6-plan section 10, C16): every log line, every response (body and headers), and the
 * SQLite files, searched for each canary. A canary is allowed in exactly the places the design delivers it:
 * <ul>
 *   <li>the approval token: only the body of a 200 {@code GET /api/intents/{id}/approval};</li>
 *   <li>a session cookie value: only the {@code Set-Cookie} header that delivers it;</li>
 *   <li>a CSRF token: only the {@code X-CSRF-Token} response header that delivers it;</li>
 *   <li>a message text: in any response (the run's own messages), but in no log line and no database file.</li>
 * </ul>
 * Everything else (the approval secret's suffix, the payment token, the model key, message texts) is allowed nowhere.
 */
final class LeakEvidence {

    /** Where a canary may appear: the part of a response it may be found in. */
    enum Allowed { NOWHERE, APPROVAL_BODY, SET_COOKIE_HEADER, CSRF_HEADER, ANY_RESPONSE }

    private final Map<String, Allowed> canaries = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, java.util.Set<String>> databaseColumns = new LinkedHashMap<>();
    private final List<ApiClient> clients = new ArrayList<>();
    private final List<Path> databases = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();

    void canary(String name, String value, Allowed allowed) {
        canaries.put(value, allowed);
        names.put(value, name);
    }

    /**
     * As {@link #canary(String, String, Allowed)}, and the database may hold it in these {@code table.column}s only: the
     * design stores the CSRF token in {@code user_session.csrf_token} (18.5) and the payment token in the stored
     * {@code complete} body, {@code merchant_attempt.request_body} (7, I7).
     */
    void canary(String name, String value, Allowed allowed, String... databaseColumns) {
        canary(name, value, allowed);
        this.databaseColumns.put(value, java.util.Set.of(databaseColumns));
    }

    void clients(ApiClient client) {
        clients.add(client);
    }

    void database(Path file) {
        databases.add(file);
    }

    void logs(List<String> lines) {
        logLines.clear();
        logLines.addAll(lines);
    }

    /** Every place a canary was found where it may not be, each as "canary name in where". */
    List<String> findings() {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Allowed> c : canaries.entrySet()) {
            String value = c.getKey();
            String name = names.get(value);
            for (int i = 0; i < logLines.size(); i++) {
                if (logLines.get(i).contains(value)) {
                    found.add(name + " in a line of " + logger(logLines.get(i)) + ": " + excerpt(logLines.get(i), value));
                }
            }
            for (ApiClient client : clients) {
                for (HttpResponse<String> r : List.copyOf(client.seen)) {
                    String where = r.request().method() + " " + r.request().uri().getPath();
                    if (c.getValue() == Allowed.ANY_RESPONSE) {
                        continue;
                    }
                    if (r.body().contains(value) && !(c.getValue() == Allowed.APPROVAL_BODY && isApprovalGet(r))) {
                        found.add(name + " in the body of " + where);
                    }
                    r.headers().map().forEach((header, values) -> {
                        for (String v : values) {
                            if (v.contains(value) && !deliveredBy(c.getValue(), header)) {
                                found.add(name + " in header " + header + " of " + where);
                            }
                        }
                    });
                }
            }
            for (Path db : databases) {
                found.addAll(databaseFindings(db, name, value));
            }
        }
        return found;
    }

    private static boolean isApprovalGet(HttpResponse<?> r) {
        return r.request().method().equals("GET") && r.statusCode() == 200
                && r.request().uri().getPath().matches("/api/intents/[^/]+/approval");
    }

    private static boolean deliveredBy(Allowed allowed, String header) {
        return allowed == Allowed.SET_COOKIE_HEADER && header.equalsIgnoreCase("set-cookie")
                || allowed == Allowed.CSRF_HEADER && header.equalsIgnoreCase("x-csrf-token");
    }

    /** Every text column of every table, except the columns the design allows the canary in. */
    private List<String> databaseFindings(Path db, String name, String value) {
        java.util.Set<String> allowed = databaseColumns.getOrDefault(value, java.util.Set.of());
        List<String> found = new ArrayList<>();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                List<String> columns = new ArrayList<>();
                try (java.sql.ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
                    while (rs.next()) {
                        columns.add(rs.getString("name"));
                    }
                }
                for (String column : columns) {
                    if (allowed.contains(table + "." + column)) {
                        continue;
                    }
                    try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM \"" + table
                            + "\" WHERE instr(CAST(\"" + column + "\" AS TEXT), ?) > 0")) {
                        ps.setString(1, value);
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) {
                                found.add(name + " in " + table + "." + column);
                            }
                        }
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }

    /** The {@code log.logger} of a structured line, or "(not a log event)" for a raw line. */
    private static String logger(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"logger\":\"([^\"]+)\"").matcher(line);
        return m.find() ? m.group(1) : "(not a log event)";
    }

    private static String excerpt(String line, String value) {
        int at = line.indexOf(value);
        return line.substring(Math.max(0, at - 40), Math.min(line.length(), at + value.length() + 20));
    }
}
