import sys
import json
import logging
import datetime
import uuid
import time
from pathlib import Path

# Add server directory to sys.path
SERVER_DIR = Path(__file__).resolve().parent
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

import os
import shutil
import subprocess
import numpy as np
import cv2
import bcrypt
import psycopg2
import psycopg2.extras
from dotenv import load_dotenv
from jose import JWTError, jwt
import pandas as pd
from fastapi import FastAPI, File, UploadFile, HTTPException, Request, Body, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from processor import ProductionAttendanceProcessor

load_dotenv(dotenv_path=str(SERVER_DIR.parent / "app" / ".env"))

# JWT Config
JWT_SECRET = "school_attendance_super_secret_key_2026"
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_HOURS = 720  # 30 days

# DB Config
DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", 5432)),
    "dbname": "AttendenceSystem",
    "user": os.getenv("DB_USER", "postgres"),
    "password": os.getenv("DB_PASSWORD", "1502")
}

def get_db():
    conn = psycopg2.connect(**DB_CONFIG)
    conn.autocommit = True
    return conn

# Setup ADB reverse port forwarding once on startup
def setup_adb_reverse():
    try:
        adb_path = shutil.which("adb")
        if not adb_path:
            sdk_adb = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
            if os.path.exists(sdk_adb):
                adb_path = sdk_adb
        if adb_path:
            subprocess.run([adb_path, "reverse", "tcp:8000", "tcp:8000"], capture_output=True, timeout=5)
    except Exception:
        pass

setup_adb_reverse()

logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(message)s")
logger = logging.getLogger("attendance_api")

app = FastAPI(
    title="School Attendance API",
    description="PostgreSQL + JWT powered attendance system",
    version="2.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Production OCR Processor (Dynamic Row Splitting)
processor = ProductionAttendanceProcessor()
OUTPUT_DIR = Path("output")
OUTPUT_DIR.mkdir(exist_ok=True)
TEMPLATES_DIR = Path("templates")

# JWT Bearer security
security = HTTPBearer()

def create_token(user_id: str, email: str) -> str:
    payload = {
        "sub": user_id,
        "email": email,
        "exp": datetime.datetime.utcnow() + datetime.timedelta(hours=JWT_EXPIRE_HOURS),
        "iat": datetime.datetime.utcnow()
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)

def verify_token(credentials: HTTPAuthorizationCredentials = Depends(security)):
    try:
        payload = jwt.decode(credentials.credentials, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user_id = payload.get("sub")
        if not user_id:
            raise HTTPException(status_code=401, detail="Invalid token")
        return payload
    except JWTError:
        raise HTTPException(status_code=401, detail="Invalid or expired token")

# ──────────────────────────────────────────
# AUTH ENDPOINTS
# ──────────────────────────────────────────

@app.post("/auth/register")
def register(body: dict = Body(...)):
    full_name   = body.get("full_name", "").strip()
    employee_id = body.get("employee_id", "").strip()
    email       = body.get("email", "").strip().lower()
    password    = body.get("password", "").strip()
    city        = body.get("city", "").strip()
    district    = body.get("district", "").strip()
    state       = body.get("state", "").strip()
    school_name = body.get("school_name", "").strip()
    school_code = body.get("school_code", "").strip()
    school_lat  = float(body.get("school_lat", 0))
    school_lng  = float(body.get("school_lng", 0))

    if not all([full_name, employee_id, email, password]):
        raise HTTPException(status_code=400, detail="full_name, employee_id, email and password are required")

    hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
    uid = str(uuid.uuid4())

    try:
        conn = get_db()
        cur = conn.cursor()
        cur.execute("""
            INSERT INTO users (uid, full_name, employee_id, email, password, city, district, state,
                               school_name, school_code, school_lat, school_lng)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        """, (uid, full_name, employee_id, email, hashed, city, district, state,
              school_name, school_code, school_lat, school_lng))
        cur.close()
        conn.close()
    except psycopg2.errors.UniqueViolation as e:
        detail = "Email already registered" if "email" in str(e) else "Employee ID already registered"
        raise HTTPException(status_code=409, detail=detail)
    except Exception as e:
        logger.error(f"Register error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

    token = create_token(uid, email)
    return {"status": "success", "message": "Registered successfully", "token": token, "uid": uid}


@app.post("/auth/login")
def login(body: dict = Body(...)):
    email    = body.get("email", "").strip().lower()
    password = body.get("password", "").strip()

    if not email or not password:
        raise HTTPException(status_code=400, detail="email and password required")

    try:
        conn = get_db()
        cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
        cur.execute("SELECT * FROM users WHERE email = %s", (email,))
        user = cur.fetchone()
        cur.close()
        conn.close()
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

    if not user:
        raise HTTPException(status_code=401, detail="No account found with this email")

    if not bcrypt.checkpw(password.encode(), user["password"].encode()):
        raise HTTPException(status_code=401, detail="Incorrect email or password")

    token = create_token(user["uid"], user["email"])
    return {
        "status": "success",
        "token": token,
        "uid": user["uid"],
        "full_name": user["full_name"],
        "email": user["email"],
        "school_name": user["school_name"],
        "school_code": user["school_code"]
    }


@app.get("/auth/me")
def get_me(token_data: dict = Depends(verify_token)):
    uid = token_data["sub"]
    try:
        conn = get_db()
        cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
        cur.execute("SELECT uid, full_name, employee_id, email, city, district, state, school_name, school_code, school_lat, school_lng FROM users WHERE uid = %s", (uid,))
        user = cur.fetchone()
        cur.close()
        conn.close()
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return dict(user)


@app.post("/auth/forgot-password")
def forgot_password(body: dict = Body(...)):
    email = body.get("email", "").strip().lower()
    if not email:
        raise HTTPException(status_code=400, detail="email required")
    try:
        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT uid FROM users WHERE email = %s", (email,))
        user = cur.fetchone()
        if not user:
            raise HTTPException(status_code=404, detail="No account found with this email")
        temp_password = "Temp@" + str(uuid.uuid4())[:8].upper()
        hashed = bcrypt.hashpw(temp_password.encode(), bcrypt.gensalt()).decode()
        cur.execute("UPDATE users SET password = %s WHERE email = %s", (hashed, email))
        cur.close()
        conn.close()
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    return {"status": "success", "temp_password": temp_password, "message": "Temporary password set. Please change after login."}


# ──────────────────────────────────────────
# SCHOOLS ENDPOINT
# ──────────────────────────────────────────

@app.get("/schools")
def list_schools():
    try:
        conn = get_db()
        cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
        cur.execute("SELECT name, code, lat, lng FROM schools ORDER BY name")
        schools = [dict(row) for row in cur.fetchall()]
        cur.close()
        conn.close()
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    return {"schools": schools}


# ──────────────────────────────────────────
# ATTENDANCE ENDPOINTS
# ──────────────────────────────────────────

@app.post("/attendance/mark")
def mark_attendance(body: dict = Body(...), token_data: dict = Depends(verify_token)):
    uid = token_data["sub"]
    today = datetime.date.today().isoformat()
    try:
        conn = get_db()
        cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
        cur.execute("SELECT full_name, school_name, school_code FROM users WHERE uid = %s", (uid,))
        user = cur.fetchone()
        if not user:
            raise HTTPException(status_code=404, detail="User not found")
        cur.execute("SELECT id FROM attendance WHERE user_id = %s AND date = %s", (uid, today))
        existing = cur.fetchone()
        if existing:
            cur.close()
            conn.close()
            return {"status": "already_marked", "message": "Attendance already marked for today"}
        ts = int(time.time() * 1000)
        cur.execute("""
            INSERT INTO attendance (user_id, username, school_name, school_code, date, status, timestamp)
            VALUES (%s, %s, %s, %s, %s, 1, %s)
        """, (uid, user["full_name"], user["school_name"], user["school_code"], today, ts))
        cur.close()
        conn.close()
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Mark attendance error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    return {"status": "success", "message": "Attendance marked successfully", "date": today}


@app.get("/attendance/check-today")
def check_today(token_data: dict = Depends(verify_token)):
    uid = token_data["sub"]
    today = datetime.date.today().isoformat()
    try:
        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT id FROM attendance WHERE user_id = %s AND date = %s", (uid, today))
        exists = cur.fetchone() is not None
        cur.close()
        conn.close()
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    return {"marked": exists, "date": today}


@app.get("/attendance/history")
def attendance_history(token_data: dict = Depends(verify_token)):
    uid = token_data["sub"]
    try:
        conn = get_db()
        cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
        cur.execute("""
            SELECT id, user_id, username, school_name, school_code, date, status, timestamp
            FROM attendance WHERE user_id = %s ORDER BY date DESC LIMIT 50
        """, (uid,))
        records = [dict(r) for r in cur.fetchall()]
        cur.close()
        conn.close()
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    return {"records": records}


# ──────────────────────────────────────────
# PORTAL / OCR ENDPOINTS (kept as-is)
# ──────────────────────────────────────────


@app.get("/portal", response_class=HTMLResponse)
def get_portal():
    index_file = TEMPLATES_DIR / "index.html"
    if index_file.exists():
        with open(index_file, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    return HTMLResponse("<h1>Monthly Attendance Portal</h1><p>index.html not found.</p>")

@app.get("/health")
def health():
    return {"status": "ok", "service": "ready"}

@app.get("/api/latest_attendance")
def get_latest_attendance():
    latest_json = OUTPUT_DIR / "attendance_latest.json"
    if latest_json.exists():
        try:
            with open(latest_json, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.error(f"Error reading latest attendance: {e}")
    return {"status": "empty", "total_students": 0, "days_count": 31, "data": []}

@app.post("/api/update_attendance")
def update_attendance(payload: dict = Body(...)):
    try:
        latest_json = OUTPUT_DIR / "attendance_latest.json"
        latest_csv = OUTPUT_DIR / "attendance_latest.csv"

        records = payload.get("data", [])
        total_days = payload.get("days_count", 31)
        class_name = payload.get("class_name", "5A")
        month = payload.get("month", datetime.datetime.now().strftime("%B"))
        year = int(payload.get("year", datetime.datetime.now().year))
        school_name = payload.get("school_name", "")
        school_code = payload.get("school_code", "")
        teacher_id = payload.get("teacher_id", "")

        # 1. Save JSON
        with open(latest_json, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)

        # 2. Save CSV
        table_rows = []
        for r in records:
            row_dict = {
                "Roll No": r.get("roll_no", ""),
                "Student Name": r.get("student_name", ""),
                "Present": r.get("present_count", 0),
                "Absent": r.get("absent_count", 0)
            }
            att = r.get("attendance", [])
            for d_idx in range(total_days):
                val = att[d_idx] if d_idx < len(att) else 0
                row_dict[f"D{d_idx + 1}"] = "P" if val == 1 else "A"
            table_rows.append(row_dict)

        df = pd.DataFrame(table_rows)
        df.to_csv(latest_csv, index=False)

        # 3. Insert / Upsert into PostgreSQL student_attendance table
        conn = get_db()
        cur = conn.cursor()
        inserted_count = 0
        logger.info(f"\n========================================================")
        logger.info(f"📥 [DB UPLOAD] Processing {len(records)} students for Class: {class_name}, Month: {month} {year}")
        logger.info(f"========================================================")

        for r in records:
            roll = str(r.get("roll_no", "")).strip()
            name = str(r.get("student_name", "")).strip()
            if not roll and not name:
                continue
            p_cnt = int(r.get("present_count", 0))
            a_cnt = int(r.get("absent_count", 0))
            att_json = json.dumps(r.get("attendance", []))
            cur.execute("""
                INSERT INTO student_attendance 
                (roll_no, student_name, class_name, month, year, days_count, present_count, absent_count, attendance, school_name, school_code, teacher_id, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())
                ON CONFLICT (roll_no, class_name, month, year)
                DO UPDATE SET
                    student_name = EXCLUDED.student_name,
                    days_count = EXCLUDED.days_count,
                    present_count = EXCLUDED.present_count,
                    absent_count = EXCLUDED.absent_count,
                    attendance = EXCLUDED.attendance,
                    school_name = EXCLUDED.school_name,
                    school_code = EXCLUDED.school_code,
                    teacher_id = EXCLUDED.teacher_id,
                    updated_at = NOW();
            """, (roll, name, class_name, month, year, total_days, p_cnt, a_cnt, att_json, school_name, school_code, teacher_id))
            inserted_count += 1
            if inserted_count <= 5 or inserted_count == len(records):
                logger.info(f"  💾 DB Row: Roll {roll:<4} | {name:<20} | P: {p_cnt:<2} | A: {a_cnt:<2}")
        
        cur.close()
        conn.close()

        logger.info(f"========================================================")
        logger.info(f"✅ [POSTGRESQL COMMIT] Successfully saved {inserted_count} student records to 'student_attendance' table!")
        logger.info(f"========================================================\n")
        return {
            "status": "success",
            "message": f"Successfully saved {inserted_count} student records to Database!",
            "total_saved": inserted_count
        }
    except Exception as e:
        logger.error(f"Error updating attendance: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/student_attendance")
def get_student_attendance(class_name: str = "5A", month: str = "", year: int = 2026):
    try:
        conn = get_db()
        cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
        if month:
            cur.execute("""
                SELECT roll_no, student_name, class_name, month, year, days_count, present_count, absent_count, attendance, school_name, school_code, updated_at
                FROM student_attendance
                WHERE class_name = %s AND month = %s AND year = %s
                ORDER BY CAST(NULLIF(regexp_replace(roll_no, '\\D', '', 'g'), '') AS INTEGER) ASC NULLS LAST, roll_no ASC
            """, (class_name, month, year))
        else:
            cur.execute("""
                SELECT roll_no, student_name, class_name, month, year, days_count, present_count, absent_count, attendance, school_name, school_code, updated_at
                FROM student_attendance
                WHERE class_name = %s AND year = %s
                ORDER BY CAST(NULLIF(regexp_replace(roll_no, '\\D', '', 'g'), '') AS INTEGER) ASC NULLS LAST, roll_no ASC
            """, (class_name, year))
        rows = [dict(r) for r in cur.fetchall()]
        cur.close()
        conn.close()
        for row in rows:
            if isinstance(row.get("attendance"), str) and row["attendance"]:
                try:
                    row["attendance"] = json.loads(row["attendance"])
                except Exception:
                    pass
        return {"status": "success", "count": len(rows), "data": rows}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/process_attendance")
async def process_attendance(image: UploadFile = File(...)):
    """
    Direct endpoint:
    Accepts raw camera photo, parses with RapidOCR + OpenCV, returns JSON,
    generates a NEW timestamped CSV and JSON file, and saves the annotated
    DEBUG DETECTION IMAGE on every scan.
    """
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    logger.info(f"\n========================================================")
    logger.info(f"[{timestamp}] Processing new attendance scan: '{image.filename}'")
    logger.info(f"========================================================")

    try:
        contents = await image.read()
        nparr = np.frombuffer(contents, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if img is None:
            raise HTTPException(status_code=400, detail="Invalid image file.")

        # [COMMENTED OUT] Raw scan saving
        # raw_scan_path = OUTPUT_DIR / f"raw_scan_{timestamp}.jpg"
        # cv2.imwrite(str(raw_scan_path), img)
        # logger.info(f"Saved raw upload image: {raw_scan_path.resolve()}")

        import importlib
        import processor as proc_module
        importlib.reload(proc_module)
        active_processor = proc_module.ProductionAttendanceProcessor()
        result = active_processor.process_image(img)
        records = result.get("data", [])

        # Save annotated debug image (Only debug_latest.png)
        if "debug_image" in result and result["debug_image"] is not None:
            debug_img = result.pop("debug_image")
            # debug_path = OUTPUT_DIR / f"debug_attendance_{timestamp}.png"
            latest_debug = OUTPUT_DIR / "debug_latest.png"
            # cv2.imwrite(str(debug_path), debug_img)
            cv2.imwrite(str(latest_debug), debug_img)
            logger.info(f"Saved annotated debug image to: {latest_debug.resolve()}")

        # [COMMENTED OUT] Coordinate DataFrames to CSV
        y_df = result.pop("horizontal_lines_df", None)
        x_df = result.pop("vertical_lines_df", None)
        table_info = result.pop("table_info_df", None)
        # if y_df is not None:
        #     y_df.to_csv(OUTPUT_DIR / f"detected_horizontal_lines_{timestamp}.csv", index=False)
        #     y_df.to_csv(OUTPUT_DIR / "detected_horizontal_lines.csv", index=False)
        # if x_df is not None:
        #     x_df.to_csv(OUTPUT_DIR / f"detected_vertical_lines_{timestamp}.csv", index=False)
        #     x_df.to_csv(OUTPUT_DIR / "detected_vertical_lines.csv", index=False)
        # if table_info is not None:
        #     table_info.to_csv(OUTPUT_DIR / f"table_detection_info_{timestamp}.csv", index=False)
        #     table_info.to_csv(OUTPUT_DIR / "table_detection_info.csv", index=False)

        # Build clean Pandas DataFrame for console display & CSV saving
        table_rows = []
        for r in records:
            row_dict = {
                "Roll No": r["roll_no"],
                "Student Name": r["student_name"],
                "Present": r["present_count"],
                "Absent": r["absent_count"]
            }
            for d_idx, val in enumerate(r.get("attendance", [])):
                row_dict[f"D{d_idx + 1}"] = "P" if val == 1 else "A"
            table_rows.append(row_dict)

        df = pd.DataFrame(table_rows)

        # Print to Server Console
        print("\n" + "=" * 80)
        print(f"ATTENDANCE EXTRACTION COMPLETE [{timestamp}]:")
        print("=" * 80)
        print(df.to_string(index=False))
        print("=" * 80 + "\n")

        # Save ONLY CSV output
        csv_path = OUTPUT_DIR / f"attendance_{timestamp}.csv"
        latest_csv = OUTPUT_DIR / "attendance_latest.csv"
        df.to_csv(csv_path, index=False)
        df.to_csv(latest_csv, index=False)

        # [COMMENTED OUT] JSON files
        # json_path = OUTPUT_DIR / f"attendance_{timestamp}.json"
        # latest_json = OUTPUT_DIR / "attendance_latest.json"
        # with open(json_path, "w", encoding="utf-8") as f:
        #     json.dump(result, f, indent=2, ensure_ascii=False)
        # with open(latest_json, "w", encoding="utf-8") as f:
        #     json.dump(result, f, indent=2, ensure_ascii=False)

        logger.info(f"Created scan CSV: {latest_csv.resolve()}")
        logger.info(f"Returning {len(records)} students to Android app.")

        return result

    except Exception as e:
        logger.error(f"Processing error: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
