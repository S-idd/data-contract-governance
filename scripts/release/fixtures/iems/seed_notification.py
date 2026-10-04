#!/usr/bin/env python3
"""Seed the disposable IEMS notification fixture for SQLite or Compose databases."""
import argparse
from pathlib import Path
import shutil
import sqlite3
import subprocess
import time


def seed_sqlite(database):
    if not database.is_file():
        raise SystemExit(f"Database does not exist: {database}")
    with sqlite3.connect(database) as connection:
        admin = connection.execute("SELECT id FROM users WHERE username = ?", ("demo-admin",)).fetchone()
        if admin is None:
            raise SystemExit("Start the IEMS demo server first to create demo-admin")
        connection.execute(
            "INSERT INTO notifications (user_id, title, message, type, is_read, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (admin[0], "Postman fixture", "Notification endpoint check", "TEST", 0, int(time.time() * 1000)),
        )


def seed_server(backend, compose_file, env_file):
    if not compose_file.is_file() or not env_file.is_file():
        raise SystemExit("Compose configuration is missing; run scripts/database-up.sh first")
    if shutil.which("docker") and subprocess.run(
            ["docker", "compose", "version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        command = ["docker", "compose"]
    elif shutil.which("podman-compose"):
        command = ["podman-compose"]
    else:
        raise SystemExit("Install Docker Compose v2 or podman-compose")
    # Credentials come from the service environment, never command-line values.
    client = ('PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 '
              '-h 127.0.0.1 -U "$POSTGRES_USER" -d iems_app') if backend == "postgres" else (
              'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP -h 127.0.0.1 '
              '-u "$MYSQL_USER" --batch iems_app')
    # A missing admin produces NULL in a NOT NULL column and fails the INSERT;
    # unlike INSERT ... SELECT, this cannot silently insert zero rows.
    sql = ("INSERT INTO notifications (user_id, title, message, type, is_read, created_at) "
           "VALUES ((SELECT id FROM users WHERE username = 'demo-admin'), "
           "'Postman fixture', 'Notification endpoint check', 'TEST', FALSE, CURRENT_TIMESTAMP);\n")
    subprocess.run(command + ["--env-file", str(env_file), "-f", str(compose_file),
                              "exec", "-T", backend, "sh", "-c", client],
                   input=sql, text=True, check=True, timeout=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", nargs="?", type=Path, help="Existing SQLite demo database")
    parser.add_argument("--backend", choices=("sqlite", "postgres", "mysql"), default="sqlite")
    parser.add_argument("--compose-file", type=Path)
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args()
    if args.backend == "sqlite":
        if args.database is None:
            parser.error("SQLite requires a database path")
        seed_sqlite(args.database)
    else:
        if args.compose_file is None or args.env_file is None or args.database is not None:
            parser.error("Server databases require --compose-file and --env-file, without a SQLite path")
        seed_server(args.backend, args.compose_file, args.env_file)
    print(f"Created a notification fixture for demo-admin ({args.backend})")


if __name__ == "__main__":
    main()
