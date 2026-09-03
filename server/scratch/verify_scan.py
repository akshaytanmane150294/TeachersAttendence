import sys
from pathlib import Path
SERVER_DIR = Path(__file__).resolve().parent.parent
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

import cv2
from processor import ProductionAttendanceProcessor

img = cv2.imread('output/raw_scan_20260825_115650.jpg')
p = ProductionAttendanceProcessor()
res = p.process_image(img)
data = res.get('data', [])
print(f"Total students extracted: {len(data)}")
for i in range(min(5, len(data))):
    st = data[i]
    print(f"  Roll: {st['roll_no']} | Name: {st['student_name']} | Present: {st['present_count']} | Absent: {st['absent_count']}")
if len(data) >= 25:
    st = data[-1]
    print(f"  Last Roll: {st['roll_no']} | Name: {st['student_name']} | Present: {st['present_count']} | Absent: {st['absent_count']}")
if 'debug_image' in res and res['debug_image'] is not None:
    cv2.imwrite('output/debug_latest.png', res['debug_image'])
    print("Saved updated output/debug_latest.png")
