"""
setup_db.py - Creates all required tables and seeds schools data
Run once: python setup_db.py
"""
import psycopg2
from psycopg2.extras import execute_values

DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "dbname": "AttendenceSystem",
    "user": "postgres",
    "password": "1502"
}

def setup():
    conn = psycopg2.connect(**DB_CONFIG)
    conn.autocommit = True
    cur = conn.cursor()
    print("Creating tables...")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id          SERIAL PRIMARY KEY,
            uid         TEXT UNIQUE NOT NULL,
            full_name   TEXT NOT NULL,
            employee_id TEXT UNIQUE NOT NULL,
            email       TEXT UNIQUE NOT NULL,
            password    TEXT NOT NULL,
            city        TEXT DEFAULT '',
            district    TEXT DEFAULT '',
            state       TEXT DEFAULT '',
            school_name TEXT DEFAULT '',
            school_code TEXT DEFAULT '',
            school_lat  DOUBLE PRECISION DEFAULT 0,
            school_lng  DOUBLE PRECISION DEFAULT 0,
            created_at  TIMESTAMP DEFAULT NOW()
        );
    """)
    print("  [OK] users table")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS attendance (
            id          SERIAL PRIMARY KEY,
            user_id     TEXT NOT NULL,
            username    TEXT DEFAULT '',
            school_name TEXT DEFAULT '',
            school_code TEXT DEFAULT '',
            date        TEXT NOT NULL,
            status      INTEGER DEFAULT 1,
            timestamp   BIGINT,
            UNIQUE (user_id, date)
        );
    """)
    print("  [OK] attendance table")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS student_attendance (
            id              SERIAL PRIMARY KEY,
            roll_no         TEXT NOT NULL,
            student_name    TEXT NOT NULL,
            class_name      TEXT DEFAULT '5A',
            month           TEXT DEFAULT '',
            year            INTEGER DEFAULT 2026,
            days_count      INTEGER DEFAULT 31,
            present_count   INTEGER DEFAULT 0,
            absent_count    INTEGER DEFAULT 0,
            attendance      TEXT DEFAULT '',
            school_name     TEXT DEFAULT '',
            school_code     TEXT DEFAULT '',
            teacher_id      TEXT DEFAULT '',
            created_at      TIMESTAMP DEFAULT NOW(),
            updated_at      TIMESTAMP DEFAULT NOW(),
            UNIQUE (roll_no, class_name, month, year)
        );
    """)
    print("  [OK] student_attendance table")
    cur.execute("""
        CREATE TABLE IF NOT EXISTS schools (
            id    SERIAL PRIMARY KEY,
            name  TEXT UNIQUE NOT NULL,
            code  TEXT DEFAULT '',
            lat   DOUBLE PRECISION DEFAULT 0,
            lng   DOUBLE PRECISION DEFAULT 0
        );
    """)
    print("  [OK] schools table")
    sample_schools = [
        ("Govt Higher Secondary School Bhilai", "GHSS001", 21.1938, 81.3786),
        ("Govt Boys Higher Secondary School Sector 9", "GBHSS009", 21.2001, 81.3695),
        ("Govt Girls Higher Secondary School Sector 6", "GGHSS006", 21.1875, 81.3840),
        ("Kendriya Vidyalaya Bhilai Steel Plant", "KVBSP001", 21.2030, 81.3750),
        ("DAV Public School Bhilai", "DAVBHILAI", 21.1950, 81.3810),
        ("Bhilai Nagar Higher Secondary School", "BNHSS001", 21.1920, 81.3700),
        ("Govt Higher Secondary School Durg", "GHSSD001", 21.1890, 81.2860),
        ("Swami Atmanand Govt English Medium School", "SAGES001", 21.2100, 81.3900),
        ("Govt Higher Secondary School Charoda", "GHSSC001", 21.2200, 81.3600),
        ("Govt Primary School Risali", "GPSR001", 21.1800, 81.4000),
    ]
    cur.execute("SELECT COUNT(*) FROM schools")
    count = cur.fetchone()[0]
    if count == 0:
        execute_values(cur, "INSERT INTO schools (name, code, lat, lng) VALUES %s ON CONFLICT (name) DO NOTHING", sample_schools)
        print(f"  [OK] Seeded {len(sample_schools)} schools")
    else:
        print(f"  [SKIP] Schools already seeded ({count} records)")
    cur.close()
    conn.close()
    print("\nDatabase setup complete!")

if __name__ == "__main__":
    setup()
