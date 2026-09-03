import psycopg2
import hashlib
import random
import datetime

DB_CONFIG = {
    "host": "127.0.0.1",
    "port": 5432,
    "dbname": "AttendenceSystem",
    "user": "postgres",
    "password": "1502"
}

def test_flow():
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    print("--- 1. Testing Non-Existent User ---")
    bad_user = "non_existent_teacher_999"
    cur.execute("SELECT teacher_code FROM mst_teacher WHERE username = %s OR teacher_code = %s", (bad_user, bad_user))
    row = cur.fetchone()
    if not row:
        print("✅ Result: You aren't Authorized")
    else:
        print("❌ Found unexpected row:", row)

    print("\n--- 2. Testing Valid Teacher ---")
    cur.execute("SELECT teacher_code, name_eng, mobile_no, username, password_hash FROM mst_teacher WHERE status = true LIMIT 1")
    teacher = cur.fetchone()
    teacher_code, name_eng, mobile_no, username, pwd_hash = teacher
    print(f"Teacher found: code={teacher_code}, name={name_eng}, mobile={mobile_no}")

    # Generate token AT-XXXX-XXXX
    part1 = f"{random.randint(0, 0xFFFF):04X}"
    part2 = f"{random.randint(0, 0xFFFF):04X}"
    token = f"AT-{part1}-{part2}"
    token_hash = hashlib.md5(token.encode('utf-8')).hexdigest()
    print(f"Generated Token: {token} (hash={token_hash})")

    # Deactivate prior
    cur.execute("UPDATE admin_tokens SET is_active = FALSE WHERE teacher_code = %s AND is_active = TRUE", (teacher_code,))

    # Insert into admin_tokens
    now = datetime.datetime.now()
    seven_days = now + datetime.timedelta(days=7)
    cur.execute("""
        INSERT INTO admin_tokens (teacher_code, username, token_plain, token_hash, token_valid_from, token_valid_until, is_active, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, TRUE, %s)
    """, (teacher_code, username or teacher_code, token, token_hash, now, seven_days, now))

    # Update mst_teacher
    cur.execute("""
        UPDATE mst_teacher
        SET login_token_hash = %s,
            token_valid_from = %s,
            token_valid_until = %s
        WHERE teacher_code = %s
    """, (token_hash, now, seven_days, teacher_code))
    conn.commit()

    print("✅ admin_tokens and mst_teacher updated successfully!")

    # Verify admin_tokens record
    cur.execute("SELECT token_plain, is_active, token_valid_until FROM admin_tokens WHERE teacher_code = %s AND is_active = TRUE", (teacher_code,))
    admin_row = cur.fetchone()
    print(f"Verified admin_tokens: plain={admin_row[0]}, active={admin_row[1]}, valid_until={admin_row[2]}")

    # Verify mst_teacher record
    cur.execute("SELECT login_token_hash, token_valid_until FROM mst_teacher WHERE teacher_code = %s", (teacher_code,))
    mst_row = cur.fetchone()
    print(f"Verified mst_teacher: hash={mst_row[0]}, valid_until={mst_row[1]}")

    print("\n--- 3. Testing Login Validation with Token ---")
    input_token = token
    input_hash = hashlib.md5(input_token.encode('utf-8')).hexdigest()
    if input_hash == mst_row[0] and mst_row[1] >= datetime.datetime.now():
        print(f"✅ LOGIN SUCCESSFUL FOR {name_eng} ({teacher_code})!")
    else:
        print("❌ Login validation failed")

    cur.close()
    conn.close()

if __name__ == "__main__":
    test_flow()
