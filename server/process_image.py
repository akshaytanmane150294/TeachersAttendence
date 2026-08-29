import sys
import json
import datetime
from pathlib import Path

# Add server directory to sys.path
SERVER_DIR = Path(__file__).resolve().parent
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

import cv2
import pandas as pd
from processor import ProductionAttendanceProcessor

def run_processing(image_path: str = "sample_attendance.jpg"):
    img_file = Path(image_path)
    if not img_file.exists():
        # Look in server directory or current directory
        server_img = Path(__file__).parent / image_path
        if server_img.exists():
            img_file = server_img
        else:
            print(f"[ERROR] Image file '{image_path}' not found!")
            print("Please provide a valid image path. Example: python process_image.py sample_attendance.jpg")
            return

    print("=" * 80)
    print(f"Reading and processing attendance image: {img_file.resolve()}")
    print("=" * 80)

    img = cv2.imread(str(img_file))
    if img is None:
        print(f"[ERROR] Could not decode image: {img_file}")
        return

    processor = ProductionAttendanceProcessor()
    result = processor.process_image(img)
    records = result.get("data", [])
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

    # Outer Output directory at workspace root
    output_dir = Path(__file__).resolve().parent.parent / "output"
    output_dir.mkdir(exist_ok=True)

    # Save annotated debug image with timestamp (debug_latest_time.png)
    if "debug_image" in result and result["debug_image"] is not None:
        debug_img = result.pop("debug_image")
        time_debug = output_dir / f"debug_latest_{timestamp}.png"
        latest_debug = output_dir / "debug_latest.png"
        cv2.imwrite(str(time_debug), debug_img)
        cv2.imwrite(str(latest_debug), debug_img)
        print(f"[+] Saved Debug Image: {time_debug.resolve()}")

    # Build clean Pandas DataFrame
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

    print("\n" + "=" * 80)
    print(f"ATTENDANCE EXTRACTION COMPLETE [{timestamp}] - TOTAL STUDENTS: {len(records)}")
    print("=" * 80)
    print(df.to_string(index=False))
    print("=" * 80 + "\n")

    # Save to timestamped CSV & latest CSV
    csv_path = output_dir / f"attendance_{timestamp}.csv"
    latest_csv = output_dir / "attendance_latest.csv"

    df.to_csv(csv_path, index=False)
    df.to_csv(latest_csv, index=False)

    # [COMMENTED OUT] JSON output
    # json_path = output_dir / f"attendance_{timestamp}.json"
    # latest_json = output_dir / "attendance_latest.json"
    # json_result = {}
    # for k, v in result.items():
    #     if isinstance(v, pd.DataFrame):
    #         json_result[k] = v.to_dict(orient="records")
    #     else:
    #         json_result[k] = v
    # with open(json_path, "w", encoding="utf-8") as f:
    #     json.dump(json_result, f, indent=2, ensure_ascii=False)
    # with open(latest_json, "w", encoding="utf-8") as f:
    #     json.dump(json_result, f, indent=2, ensure_ascii=False)

    print("Generated Output Files:")
    print(f"  1. CSV Output   : {csv_path.resolve()}")
    print(f"  2. JSON Output  : {json_path.resolve()}")
    print(f"  3. Latest CSV   : {latest_csv.resolve()}")
    print(f"  4. Latest JSON  : {latest_json.resolve()}")
    print("=" * 80)

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "sample_attendance.jpg"
    run_processing(target)
