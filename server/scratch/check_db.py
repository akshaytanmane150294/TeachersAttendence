import sqlite3
import datetime

conn = sqlite3.connect('local_phone_db.db')
cur = conn.cursor()

cur.execute("SELECT COUNT(*) FROM student_attendance_local")
total = cur.fetchone()[0]
print(f"=== Total Records in SQLite: {total} ===")

cur.execute("""
    SELECT id, roll_no, student_name, class_name, month, year, present_count, absent_count, saved_timestamp, school_code, school_name, is_synced 
    FROM student_attendance_local 
    ORDER BY saved_timestamp DESC, roll_no ASC
""")
rows = cur.fetchall()

for r in rows:
    time_str = datetime.datetime.fromtimestamp(r[8]/1000.0).strftime('%Y-%m-%d %H:%M:%S')
    print(f"ID: {r[0]} | Roll: {r[1]:<5} | Name: {r[2]:<18} | Class: {r[3]} | Month/Year: {r[4]} {r[5]} | Present: {r[6]:<2} | Absent: {r[7]:<2} | Saved: {time_str} | Synced: {r[11]} | School: {r[9]} ({r[10]})")
