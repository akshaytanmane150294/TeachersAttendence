"""
setup_tokens.py - Executes generate_tokens.sql in PostgreSQL
"""
import os
from pathlib import Path
import psycopg2

SERVER_DIR = Path(__file__).resolve().parent
SQL_FILE = SERVER_DIR / "generate_tokens.sql"

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", 5432)),
    "dbname": "AttendenceSystem",
    "user": os.getenv("DB_USER", "postgres"),
    "password": os.getenv("DB_PASSWORD", "1502")
}

def main():
    print("Connecting to PostgreSQL...")
    conn = psycopg2.connect(**DB_CONFIG)
    conn.autocommit = True
    cur = conn.cursor()

    print(f"Executing {SQL_FILE.name}...")
    with open(SQL_FILE, "r", encoding="utf-8") as f:
        sql = f.read()

    cur.execute(sql)
    print("✅ admin_tokens table and generate_7day_tokens() procedure created successfully!")

    cur.close()
    conn.close()

if __name__ == "__main__":
    main()
