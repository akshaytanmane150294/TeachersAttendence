import cv2
import numpy as np
import pandas as pd
import json
import difflib
from collections import Counter
from dataclasses import dataclass
from typing import List, Optional, Tuple

@dataclass
class AttendanceConfig:
    horizontal_kernel_ratio: float = 0.08
    vertical_kernel_ratio: float = 0.08
    cell_margin_ratio: float = 0.18
    text_column_margin_px: int = 3
    min_ink_ratio: float = 0.025
    min_component_area: int = 12
    enable_roll_sequence_correction: bool = True
    attendance_summary_columns: int = 2

def preprocess(image):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 21, 7)
    return binary

def merge_positions(positions, max_gap=5):
    if len(positions) == 0: return []
    positions = sorted(map(int, positions))
    groups = []
    start = positions[0]
    previous = positions[0]
    for current in positions[1:]:
        if current - previous <= max_gap:
            previous = current
        else:
            groups.append((start, previous))
            start = current
            previous = current
    groups.append((start, previous))
    return [int((start + end) / 2) for start, end in groups]

def detect_lines(binary, ratio, is_horizontal=True):
    height, width = binary.shape
    kernel_size = max(30, int((width if is_horizontal else height) * ratio))
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (kernel_size, 1) if is_horizontal else (1, kernel_size))
    lines = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
    projection = np.sum(lines > 0, axis=1 if is_horizontal else 0)
    threshold = (width if is_horizontal else height) * 0.45
    return merge_positions(np.where(projection > threshold)[0])

def detect_ink(cell, config):
    if cell.size == 0: return False
    gray = cv2.cvtColor(cell, cv2.COLOR_BGR2GRAY)
    binary = cv2.threshold(gray, 150, 255, cv2.THRESH_BINARY_INV)[1]
    kernel = np.ones((2, 2), np.uint8)
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
    h, w = binary.shape
    border = max(1, int(min(h, w) * 0.05))
    binary[:border, :] = 0; binary[-border:, :] = 0; binary[:, :border] = 0; binary[:, -border:] = 0
    ink_ratio = cv2.countNonZero(binary) / float(binary.shape[0] * binary.shape[1])
    if ink_ratio >= config.min_ink_ratio: return True
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    for i in range(1, num_labels):
        if stats[i, cv2.CC_STAT_AREA] >= config.min_component_area: return True
    return False

def process_attendance(image_path, ocr_data_json):
    """
    image_path: path to the image
    ocr_data_json: A JSON string containing roll and name OCR results from ML Kit
                  Format: {"rows": [{"roll": "101", "name": "Aarav"}, ...]}
    """
    config = AttendanceConfig()
    img = cv2.imread(image_path)
    if img is None: return json.dumps({"error": "Image not loaded"})
    
    binary = preprocess(img)
    h_lines = detect_lines(binary, config.horizontal_kernel_ratio, True)
    v_lines = detect_lines(binary, config.vertical_kernel_ratio, False)
    
    if len(h_lines) < 2 or len(v_lines) < 3:
        return json.dumps({"error": f"Grid not detected correctly (H:{len(h_lines)}, V:{len(v_lines)})"})

    header_bottom = h_lines[1]
    student_y = [y for y in h_lines if y >= header_bottom]
    day_x = v_lines[2 : len(v_lines) - config.attendance_summary_columns]

    ocr_input = json.loads(ocr_data_json)
    ocr_rows = ocr_input.get("rows", [])
    
    records = []
    for i in range(len(student_y) - 1):
        y1, y2 = student_y[i], student_y[i+1]
        
        # Get OCR data for this row if available, otherwise empty
        row_info = ocr_rows[i] if i < len(ocr_rows) else {"roll": "", "name": "Unknown"}
        roll = row_info.get("roll", "")
        name = row_info.get("name", "Unknown")
        
        attendance = {}
        has_presence = False
        for j in range(len(day_x) - 1):
            x1, x2 = day_x[j], day_x[j+1]
            cell = img[y1:y2, x1:x2]
            is_present = detect_ink(cell, config)
            # Use 1-based day indexing
            attendance[str(j + 1)] = 1 if is_present else 0
            if is_present: has_presence = True
            
        if roll or has_presence:
            records.append({"rollNo": roll, "name": name, "attendance": attendance})

    # Sequence correction for roll numbers (Ported logic)
    if config.enable_roll_sequence_correction and records:
        rolls = [r["rollNo"] for r in records]
        parsed = [int(r) if r.isdigit() else None for r in rolls]
        diffs = [parsed[k] - parsed[k-1] for k in range(1, len(parsed)) if parsed[k] and parsed[k-1]]
        step = Counter(diffs).most_common(1)[0][0] if diffs else 1
        anchor_idx = next((k for k, v in enumerate(parsed) if v is not None), None)
        if anchor_idx is not None:
            anchor_val = parsed[anchor_idx]
            for k in range(len(records)):
                expected = anchor_val + (k - anchor_idx) * step
                records[k]["rollNo"] = str(expected)

    return json.dumps({"students": records})
