#!/usr/bin/env python3
"""Verify bridge SQL on an ephemeral, socket-only MySQL 8.4 instance.

Uses already installed mysql/mysqld binaries. Never reads .env, client option
files, or connects to an existing server. The temporary server is shut down
and its synthetic data directory removed on success or failure.
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "src/main/resources/db/migration"
INIT = ROOT / "src/main/resources/db/init/schema-mysql.sql"
V6 = (MIGRATIONS / "V6__unified_knowledge.sql").read_text(encoding="utf-8")
V7 = (MIGRATIONS / "V7__complete_unified_knowledge.sql").read_text(encoding="utf-8")
TABLES = {"kb_query_log", "kb_ingest_task", "kb_document", "kb_review_task", "kb_logical_document"}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class IsolatedMySQL:
    def __init__(self, mysql, mysqld, directory):
        self.mysql = mysql
        self.mysqld = mysqld
        self.directory = Path(directory)
        self.socket = self.directory / "mysql.sock"
        self.process = None

    def __enter__(self):
        try:
            self.start()
        except BaseException:
            self.stop()
            raise
        return self

    def start(self):
        self.data = self.directory / "data"
        self.data.mkdir()
        self.log = self.directory / "mysqld.log"
        initialized = subprocess.run([
            self.mysqld, "--no-defaults", "--initialize-insecure",
            f"--datadir={self.data}", f"--log-error={self.log}",
        ], capture_output=True, text=True, timeout=60)
        if initialized.returncode != 0:
            details = self.log.read_text() if self.log.exists() else initialized.stderr
            raise RuntimeError("Temporary MySQL initialization failed:\n" + details)
        self.process = subprocess.Popen([
            self.mysqld, "--no-defaults", f"--datadir={self.data}",
            f"--socket={self.socket}", f"--pid-file={self.directory / 'mysqld.pid'}",
            f"--log-error={self.log}", "--skip-networking", "--mysqlx=OFF",
            "--innodb-buffer-pool-size=64M",
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(self.log.read_text())
            if self.socket.exists():
                result = self.sql("SELECT VERSION();", check=False)
                if result.returncode == 0:
                    require(result.stdout.strip().startswith("8.4."), "Requires MySQL 8.4")
                    print("Isolated MySQL " + result.stdout.strip(), flush=True)
                    return
            time.sleep(0.1)
        raise TimeoutError("Temporary MySQL did not become ready")

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            try:
                self.sql("SHUTDOWN;", check=False)
                self.process.wait(timeout=10)
            except (subprocess.TimeoutExpired, OSError):
                self.process.terminate()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=10)

    def __exit__(self, *unused):
        self.stop()

    def sql(self, sql, database=None, check=True):
        command = [self.mysql, "--no-defaults", "--protocol=SOCKET", f"--socket={self.socket}",
                   "--user=root", "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]
        if database:
            require(re.fullmatch(r"kb_schema_test_[a-z_]+", database), "Unexpected database name")
            command.append(database)
        environment = dict(os.environ)
        environment.pop("MYSQL_PWD", None)
        environment.pop("MYSQL_TEST_LOGIN_FILE", None)
        # --no-login-paths prevents mysql from reading ~/.mylogin.cnf as well.
        command.insert(2, "--no-login-paths")
        return subprocess.run(command, input=sql, text=True, encoding="utf-8", capture_output=True,
                              check=check, timeout=60, env=environment)

    def database(self, suffix, baseline=False):
        name = "kb_schema_test_" + suffix
        self.sql(f"CREATE DATABASE {name} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        if baseline:
            for version in range(1, 6):
                path, = MIGRATIONS.glob(f"V{version}__*.sql")
                self.sql(path.read_text(encoding="utf-8"), name)
        return name

    def structure(self, database):
        # Column order and comments can legitimately differ in older initialization paths.
        queries = [
            "SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME;",
            "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,"
            "CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,COLUMN_NAME;",
            "SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,"
            "INDEX_TYPE,IS_VISIBLE FROM information_schema.STATISTICS "
            "WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX;",
        ]
        return tuple(self.sql(query, database).stdout for query in queries)

    def show_create(self, database):
        return self.sql("\n".join(f"SHOW CREATE TABLE {table};" for table in sorted(TABLES)), database).stdout


def seed(mysql, database, retry=False):
    retry_columns, retry_values = (",retry_count", ",7") if retry else ("", "")
    mysql.sql(f"""
INSERT INTO kb_query_log(request_id,user_id,question,route,status,response_json)
VALUES('synthetic-query','test-user','test question','KB_ONLY','COMPLETED',JSON_OBJECT('ok',TRUE));
INSERT INTO kb_ingest_task(id,request_id,source_channel,source_type,user_id,status,review_status,content_hash{retry_columns})
VALUES(41,'synthetic-task','test','MARKDOWN','test-user','FAILED','PENDING','synthetic-hash'{retry_values});
INSERT INTO kb_document(id,task_id,knowledge_type,title,review_status,status,version)
VALUES(51,41,'GUIDE','synthetic document','PENDING','FAILED',3);
INSERT INTO kb_review_task(task_id,review_status,reviewer,`comment`)
VALUES(41,'PENDING','test-reviewer','synthetic review');
""", database)


def preserved(mysql, database, expected_retry):
    require(mysql.sql("SELECT request_id,user_id,question,status,JSON_EXTRACT(response_json,'$.ok') "
                      "FROM kb_query_log;", database).stdout.strip() ==
            "synthetic-query\ttest-user\ttest question\tCOMPLETED\ttrue", "Query log changed")
    require(mysql.sql("SELECT id,request_id,content_hash,retry_count FROM kb_ingest_task;", database).stdout.strip() ==
            f"41\tsynthetic-task\tsynthetic-hash\t{expected_retry}", "Task or retry_count changed")
    require(mysql.sql("SELECT id,task_id,title,version FROM kb_document;", database).stdout.strip() ==
            "51\t41\tsynthetic document\t3", "Document changed")
    require(mysql.sql("SELECT task_id,reviewer,`comment` FROM kb_review_task;", database).stdout.strip() ==
            "41\ttest-reviewer\tsynthetic review", "Review changed")


def complete(mysql, database, expected, expected_retry=0):
    mysql.sql(V7, database)
    require(mysql.structure(database) == expected, f"Schema differs: {database}")
    preserved(mysql, database, expected_retry)
    before = mysql.show_create(database)
    mysql.sql(V7, database)
    require(mysql.show_create(database) == before, "Repeated V7 changed schema")
    preserved(mysql, database, expected_retry)


def verify(mysql):
    reference = mysql.database("reference", baseline=True)
    mysql.sql(V6, reference)
    expected = mysql.structure(reference)
    actual_tables = set(mysql.sql("SHOW TABLES;", reference).stdout.splitlines())
    require(actual_tables == TABLES, "Unexpected table inventory")

    fresh = mysql.database("fresh")
    init_sql = INIT.read_text(encoding="utf-8")
    require(not re.search(r"^\s*(ALTER|DROP|TRUNCATE|INSERT|REPLACE)\b", init_sql, re.M | re.I),
            "Initialization must be CREATE-only")
    mysql.sql(init_sql, fresh)
    require(mysql.structure(fresh) == expected, "Fresh schema differs from V1-V6")
    require(mysql.sql("\n".join(f"SELECT COUNT(*) FROM {table};" for table in sorted(TABLES)), fresh)
            .stdout.splitlines() == ["0"] * len(TABLES), "Unexpected seed data")
    for entity in (ROOT / "src/main/java/com/openclaw/kbbridge/entity").glob("*Entity.java"):
        source = entity.read_text(encoding="utf-8")
        table = re.search(r'@TableName\("(\w+)"\)', source)
        if table:
            columns = set(mysql.sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                                    f"WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table[1]}';", fresh)
                          .stdout.splitlines())
            fields = re.findall(r"private \w+ (\w+);", source)
            mapped = {re.sub(r"(?<!^)(?=[A-Z])", "_", field).lower() for field in fields}
            require(mapped <= columns, f"Missing entity columns: {entity.name}: {mapped - columns}")
    print("PASS: fresh five-table initialization matches V1-V6 types/defaults/indexes/collations and current entities", flush=True)

    missing = mysql.database("missing_retry", baseline=True)
    seed(mysql, missing)
    complete(mysql, missing, expected)
    print("PASS: V1-V5 without retry_count upgrades and initializes existing row to zero", flush=True)

    existing = mysql.database("existing_retry", baseline=True)
    mysql.sql("ALTER TABLE kb_ingest_task ADD COLUMN retry_count INT NOT NULL DEFAULT 0 "
              "COMMENT '补偿重试次数';", existing)
    seed(mysql, existing, retry=True)
    failed = mysql.sql(V6, existing, check=False)
    require(failed.returncode != 0 and "ERROR 1060" in failed.stderr and "retry_count" in failed.stderr,
            "V6 duplicate retry_count failure was not reproduced")
    require("kb_logical_document" in mysql.sql("SHOW TABLES;", existing).stdout,
            "Failed V6 should retain its successfully created logical table")
    complete(mysql, existing, expected, expected_retry=7)
    require(mysql.sql("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                      "AND TABLE_NAME='kb_ingest_task' AND COLUMN_NAME='retry_count';", existing)
            .stdout.strip() == "补偿重试次数", "Existing column definition changed")
    print("PASS: reproduced V6 ERROR 1060; V7 completes interrupted upgrade and preserves retry_count=7", flush=True)

    statements = [statement.strip() + ";" for statement in re.sub(r"--[^\n]*", "", V6).split(";") if statement.strip()]
    partial = mysql.database("partial", baseline=True)
    seed(mysql, partial)
    mysql.sql("\n".join(statements[:2]), partial)
    complete(mysql, partial, expected)
    print("PASS: V6 interrupted after ingest ALTER completes document fields and indexes", flush=True)

    indexes = mysql.database("missing_indexes", baseline=True)
    seed(mysql, indexes)
    ingest_columns = re.sub(r",\s*ADD INDEX[^;]+", "", statements[1])
    mysql.sql(statements[0] + ingest_columns + "ALTER TABLE kb_document ADD COLUMN document_id CHAR(36) NULL, "
              "ADD COLUMN release_id CHAR(36) NULL;", indexes)
    complete(mysql, indexes, expected)
    print("PASS: existing V6 columns with missing indexes and partial document fields are completed", flush=True)

    before = mysql.show_create(reference)
    restored = mysql.sql("SET SESSION group_concat_max_len=128;\n" + V7 +
                         "\nSELECT @@SESSION.group_concat_max_len;", reference).stdout.strip()
    require(restored == "128", "Session setting was not restored")
    require(mysql.show_create(reference) == before, "Applied V6 schema changed")
    print("PASS: applied V6 is unchanged; compatibility SQL restores session settings", flush=True)

    mysql.sql("INSERT INTO kb_logical_document(document_id,source_ref,source,source_rev_no,desired_seq,"
              "effective_release_id) VALUES('00000000-0000-0000-0000-000000000001','synthetic-ref','BLOG',4,8,"
              "'00000000-0000-0000-0000-000000000002');", existing)
    logical_before = mysql.sql("SELECT * FROM kb_logical_document;", existing).stdout
    mysql.sql(V7, existing)
    require(mysql.sql("SELECT * FROM kb_logical_document;", existing).stdout == logical_before,
            "Existing logical document changed")
    preserved(mysql, existing, 7)
    print("PASS: completed schema and existing logical document remain unchanged on repeated V7", flush=True)
    print("All 7 MySQL schema scenarios passed.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default=shutil.which("mysql"))
    parser.add_argument("--mysqld", default=shutil.which("mysqld"))
    args = parser.parse_args()
    require(args.mysql and args.mysqld, "Pass paths to already installed mysql and mysqld binaries")
    # A short path avoids the Unix socket path length limit.
    with tempfile.TemporaryDirectory(prefix="kb-sql-") as directory:
        with IsolatedMySQL(args.mysql, args.mysqld, directory) as mysql:
            verify(mysql)
    print("Temporary MySQL stopped; synthetic data directory removed.", flush=True)


if __name__ == "__main__":
    main()
