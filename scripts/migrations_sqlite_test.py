"""Every migration, opened with this Python's own sqlite3 (DESIGN 10).

The service migrates with sqlite-jdbc, whose SQLite grows its parser stack as needed. The scripts read the same file
with Python's sqlite3, which on macOS is the system SQLite with a fixed-size parser stack: a CHECK it cannot parse makes
the whole schema unreadable ("malformed database schema (agent_run) - parser stack overflow", the live re-check of #31).
This applies each migration in version order, as Flyway does, then reopens the file read-only, as the scripts do, and
reads every table's schema and a run row with a cause.

Run with: python3 -I scripts/migrations_sqlite_test.py (not part of ./mvnw verify, which needs no Python).
"""

import os
import re
import sqlite3
import tempfile
import unittest

MIGRATIONS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "agent-persistence", "src", "main",
                          "resources", "db", "migration")


def migrations():
    names = [n for n in os.listdir(MIGRATIONS) if re.match(r"V\d+__.+\.sql$", n)]
    return sorted(names, key=lambda n: int(re.match(r"V(\d+)__", n).group(1)))


class MigrationsOnPythonSqliteTest(unittest.TestCase):

    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.db = os.path.join(self.work, "ucplab.db")

    def tearDown(self):
        for name in os.listdir(self.work):
            os.remove(os.path.join(self.work, name))
        os.rmdir(self.work)

    def migrate(self):
        c = sqlite3.connect(self.db)
        try:
            for name in migrations():
                with open(os.path.join(MIGRATIONS, name), encoding="utf-8") as f:
                    try:
                        c.executescript(f.read())
                    except sqlite3.Error as e:
                        self.fail("%s on SQLite %s: %s" % (name, sqlite3.sqlite_version, e))
            c.commit()
        finally:
            c.close()

    # Covers: 10 (every migration applies on this SQLite, and the migrated file's schema reads back read-only, table by
    # table, as the scripts open it)
    def test_everyMigration_appliesAndItsSchemaReadsBack(self):
        self.assertGreaterEqual(len(migrations()), 12)
        self.migrate()
        with sqlite3.connect("file:%s?mode=ro" % self.db, uri=True) as c:
            tables = [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type = 'table'")]
            self.assertIn("agent_run", tables)
            for table in tables:
                self.assertTrue(c.execute("SELECT name FROM pragma_table_info(?)", (table,)).fetchall(), table)
            self.assertIn("cause", [r[0] for r in c.execute("SELECT name FROM pragma_table_info('agent_run')")])

    # Covers: 10 V12, 6.5 (V12's CHECK runs on this SQLite: a model_error keeps its cause, and a cause without
    # model_error is refused)
    def test_v12_storesAModelErrorsCause_andRefusesOneWithoutIt(self):
        self.migrate()
        with sqlite3.connect(self.db) as c:
            run = ("INSERT INTO agent_run (id, user_id, model, started_at, updated_at, ended_at, outcome, reason, cause)"
                   " VALUES (?, 'u', 'm', 't', 't', 't', 'failed', ?, ?)")
            c.execute(run, ("r", "model_error", "http_504"))
            with self.assertRaises(sqlite3.IntegrityError):
                c.execute(run, ("t", "tool_error", "http_504"))
            self.assertEqual(c.execute("SELECT cause FROM agent_run WHERE id = 'r'").fetchone(), ("http_504",))


if __name__ == "__main__":
    unittest.main()
