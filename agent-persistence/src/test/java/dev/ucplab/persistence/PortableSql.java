package dev.ucplab.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A lint for migrations: syntax that works on SQLite only (section 10, ADR 0002), and nesting deeper than an SQLite
 * build with a fixed parser stack can read (macOS's {@code sqlite3} and Python's overflow at 31 nested calls). Comments
 * and string literals are ignored.
 */
final class PortableSql {

    private record Rule(Pattern pattern, String message) {
    }

    private static final List<Rule> RULES = List.of(
            rule("\\bAUTOINCREMENT\\b", "AUTOINCREMENT is SQLite only; assign sequence numbers in the transaction"),
            rule("\\bWITHOUT\\s+ROWID\\b", "WITHOUT ROWID is SQLite only"),
            rule("\\)\\s*STRICT\\b", "STRICT tables are SQLite only"),
            rule("\\browid\\b", "rowid is SQLite only"),
            rule("\\bINTEGER\\s+PRIMARY\\s+KEY\\b", "INTEGER PRIMARY KEY is a rowid alias on SQLite only"),
            rule("\\bINSERT\\s+OR\\s+(REPLACE|IGNORE|ROLLBACK|ABORT|FAIL)\\b", "INSERT OR ... is SQLite only"),
            rule("\\bREPLACE\\s+INTO\\b", "REPLACE INTO is SQLite only"),
            rule("\\bPRAGMA\\b", "PRAGMA is SQLite only; connection settings belong to Database"),
            rule("\\b(ATTACH|DETACH)\\s+DATABASE\\b", "ATTACH and DETACH are SQLite only"),
            rule("\\bVACUUM\\b", "VACUUM differs between databases"),
            rule("\\bGLOB\\b", "GLOB is SQLite only"),
            rule("\\b(IFNULL|STRFTIME|JULIANDAY)\\s*\\(", "SQLite-only function; use COALESCE and bind times as parameters"),
            rule("\\b(datetime|date)\\s*\\(\\s*'now'", "SQLite date function; times come from the injected Clock"),
            rule("`", "backtick quoting is not standard SQL"),
            rule("\\bAS\\s+\\(.*\\)\\s+(STORED|VIRTUAL)\\b", "generated columns differ between databases"));

    /** Section 10: the deepest parenthesis nesting a migration may use; today's deepest is 4. */
    static final int MAX_NESTING = 8;

    private PortableSql() {
    }

    private static int nesting(String code) {
        int depth = 0;
        int deepest = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                deepest = Math.max(deepest, ++depth);
            } else if (c == ')') {
                depth--;
            }
        }
        return deepest;
    }

    private static Rule rule(String regex, String message) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL), message);
    }

    /** One message per rule the script breaks; empty when it stays in the portable subset. */
    static List<String> violations(String sql) {
        String code = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
        List<String> found = new ArrayList<>();
        for (Rule r : RULES) {
            if (r.pattern().matcher(code).find()) {
                found.add(r.message());
            }
        }
        int depth = nesting(code.replaceAll("'(?:[^']|'')*'", "''"));
        if (depth > MAX_NESTING) {
            found.add("nesting " + depth + " deep: more than " + MAX_NESTING
                    + ", which an SQLite with a fixed parser stack (macOS, Python) cannot parse");
        }
        return found;
    }
}
