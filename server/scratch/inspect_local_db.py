import sqlite3

conn = sqlite3.connect('local_saved.db')
cur = conn.cursor()
cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = cur.fetchall()
print("Tables:", tables)

for t in tables:
    name = t[0]
    if name == "android_metadata":
        continue
    cur.execute(f"SELECT COUNT(*) FROM {name}")
    count = cur.fetchone()[0]
    print(f"Table '{name}' has {count} records.")
    cur.execute(f"SELECT roll_no, student_name, class_name, month, year, present_count, is_synced FROM {name} LIMIT 5")
    rows = cur.fetchall()
    print("Sample rows:")
    for r in rows:
        print(" ", r)
