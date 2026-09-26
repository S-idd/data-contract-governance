#!/usr/bin/env python3
"""Create a notification fixture in an isolated SQLite IEMS demo database."""
import sqlite3
import sys
import time
from pathlib import Path


if len(sys.argv) != 2:
    raise SystemExit("Usage: seed_notification.py PATH_TO_DEMO_SQLITE_DB")

database = Path(sys.argv[1])
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
print("Created a notification fixture for demo-admin")
