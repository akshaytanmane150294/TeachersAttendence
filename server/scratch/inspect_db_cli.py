import os
import sys
import subprocess
import sqlite3
import tempfile
import datetime

def find_adb():
    local_app_data = os.environ.get('LOCALAPPDATA', '')
    sdk_adb = os.path.join(local_app_data, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
    if os.path.exists(sdk_adb):
        return sdk_adb
    return 'adb'

def get_connected_device(adb):
    try:
        out = subprocess.check_output([adb, 'devices']).decode('utf-8')
        lines = [line.strip() for line in out.split('\n') if line.strip() and not line.startswith('List of')]
        for line in lines:
            if line.endswith('\tdevice') or line.endswith(' device'):
                if '\tdevice' in line:
                    return line.rsplit('\tdevice', 1)[0].strip()
                else:
                    return line.rsplit(' device', 1)[0].strip()
    except Exception:
        pass
    return None

def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
    print("=" * 80)
    print("  [*] School Attendance - Local SQLite Live Inspector")
    print("=" * 80)
    
    adb = find_adb()
    device = get_connected_device(adb)
    
    if not device:
        print("\n[!] ERROR: No Android device detected!")
        print("Please ensure your phone is connected via USB or Wireless ADB.")
        return 1

    print(f"Connected Device: {device}")
    print("Fetching live database from app storage...")
    
    try:
        raw_db = subprocess.check_output([
            adb, '-s', device, 'exec-out', 'run-as', 'com.school.attendance',
            'cat', 'databases/school_attendance_local.db'
        ])
    except subprocess.CalledProcessError as e:
        print(f"[!] ERROR: Failed to read database from device: {e}")
        return 1

    if not raw_db or len(raw_db) < 100:
        print("[-] Database is empty or not created yet.")
        return 0

    temp_db = tempfile.NamedTemporaryFile(delete=False, suffix='.db')
    temp_path = temp_db.name
    try:
        temp_db.write(raw_db)
        temp_db.close()

        conn = sqlite3.connect(temp_path)
        cur = conn.cursor()

        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='student_attendance_local'")
        if not cur.fetchone():
            print("[-] Table 'student_attendance_local' not found yet.")
            return 0

        cur.execute("SELECT COUNT(*) FROM student_attendance_local")
        total_count = cur.fetchone()[0]

        cur.execute("""
            SELECT roll_no, student_name, class_name, month, year, present_count, absent_count, saved_timestamp, school_code, school_name, is_synced 
            FROM student_attendance_local 
            ORDER BY year DESC, month DESC, CAST(roll_no AS INTEGER) ASC, roll_no ASC
        """)
        rows = cur.fetchall()

        print(f"\nTotal Saved Records: {total_count}")
        print("-" * 105)
        print(f"{'Roll':<6} | {'Student Name':<20} | {'Class':<6} | {'Month/Year':<16} | {'P':<3} | {'A':<3} | {'Synced':<7} | {'Last Saved Time'}")
        print("-" * 105)

        for r in rows:
            roll = str(r[0])
            name = str(r[1])[:19]
            cls = str(r[2])
            my = f"{r[3]} {r[4]}"
            p = str(r[5])
            a = str(r[6])
            synced = "Yes [OK]" if r[10] == 1 else "No"
            time_str = datetime.datetime.fromtimestamp(r[7]/1000.0).strftime('%Y-%m-%d %H:%M:%S') if r[7] else "N/A"
            print(f"{roll:<6} | {name:<20} | {cls:<6} | {my:<16} | {p:<3} | {a:<3} | {synced:<7} | {time_str}")

        print("-" * 105)
        if rows:
            school_code = rows[0][8]
            school_name = rows[0][9]
            print(f"School: {school_name} (Code: {school_code})")
        print("=" * 105)

    finally:
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception:
                pass

    return 0

if __name__ == '__main__':
    sys.exit(main())
