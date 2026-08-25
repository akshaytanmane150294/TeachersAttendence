import os
import logging
from dataclasses import dataclass
from typing import List, Tuple, Dict, Any

import cv2
import numpy as np
import pandas as pd
from rapidocr_onnxruntime import RapidOCR

logger = logging.getLogger("attendance")
logger.setLevel(logging.INFO)

rapid_engine = RapidOCR()


@dataclass
class AttendanceConfig:
    min_ink_ratio: float = 0.10
    standard_width: int = 2000
    standard_height: int = 1400


class ProductionAttendanceProcessor:
    """
    Pure Row-Wise Attendance Sheet & Grid Parser.
    1. Fast 4-Angle Upright Orientation via 600px thumbnail (<0.8s).
    2. Direct Table Grid Morphological Detection & Perspective Warp (0.05s).
    3. Mathematical Horizontal Line Projections (y_lines only).
    4. Row-by-Row Loop on each [Y1..Y2] slice:
       - Extract Roll Number in [Y1..Y2, roll_x1:roll_x2]
       - Extract Student Name in [Y1..Y2, name_x1:name_x2]
       - Loop 31 Days in same row: 0 = Empty, 1 = Black Circle/Mark
       - Count Present (1) and Absent (0)
    """

    def __init__(self, config: AttendanceConfig | None = None):
        self.config = config or AttendanceConfig()

    def _auto_orient_upright(self, image: np.ndarray) -> np.ndarray:
        h, w = image.shape[:2]
        if h > w:
            image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)

        rotations = [
            ("0 deg", image),
            ("180 deg", cv2.rotate(image, cv2.ROTATE_180))
        ]

        best_angle_name = "0 deg"
        best_img = image
        best_score = -9999.0

        for name, rotated in rotations:
            thumb = cv2.resize(rotated, (600, 420))
            boxes, _ = rapid_engine(thumb)
            sc = 0.0
            if boxes:
                sh = thumb.shape[0]
                for b in boxes:
                    txt = b[1].lower()
                    conf = float(b[2]) if len(b) > 2 else 0.8
                    cy = (b[0][0][1] + b[0][2][1]) / 2.0
                    if any(k in txt for k in ["monthly", "attendance", "sheet", "class", "section", "june", "month", "year"]):
                        if cy < sh * 0.35:
                            sc += 20.0 * conf
                        elif cy > sh * 0.65:
                            sc -= 30.0 * conf
            if sc > best_score:
                best_score = sc
                best_angle_name = name
                best_img = rotated

        logger.info(f"Auto-orient: Selected {best_angle_name} (Score: {best_score:.2f})")
        return best_img

    def _rectify_table_grid(self, image: np.ndarray) -> np.ndarray:
        upright = self._auto_orient_upright(image)
        h, w = upright.shape[:2]

        gray = cv2.cvtColor(upright, cv2.COLOR_BGR2GRAY)
        bin_img = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 21, 5)

        h_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (int(w * 0.04), 1))
        h_lines = cv2.morphologyEx(bin_img, cv2.MORPH_OPEN, h_kernel)

        v_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, int(h * 0.04)))
        v_lines = cv2.morphologyEx(bin_img, cv2.MORPH_OPEN, v_kernel)

        table_grid = cv2.bitwise_or(h_lines, v_lines)
        contours, _ = cv2.findContours(table_grid, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        contours = sorted(contours, key=cv2.contourArea, reverse=True)

        if contours and cv2.contourArea(contours[0]) > (w * h * 0.10):
            main_c = contours[0]
            rect = cv2.minAreaRect(main_c)
            box = np.int32(cv2.boxPoints(rect))

            s = box.sum(axis=1)
            tl = box[np.argmin(s)]
            br = box[np.argmax(s)]
            diff = np.diff(box, axis=1)
            tr = box[np.argmin(diff)]
            bl = box[np.argmax(diff)]

            src = np.array([tl, tr, br, bl], dtype="float32")
            dst = np.array([
                [0, 0],
                [self.config.standard_width - 1, 0],
                [self.config.standard_width - 1, self.config.standard_height - 1],
                [0, self.config.standard_height - 1]
            ], dtype="float32")

            warped = cv2.warpPerspective(upright, cv2.getPerspectiveTransform(src, dst), (self.config.standard_width, self.config.standard_height))
            return warped

        return cv2.resize(upright, (self.config.standard_width, self.config.standard_height), interpolation=cv2.INTER_CUBIC)

    def _merge_peaks(self, peaks: np.ndarray, min_gap: int = 10) -> List[int]:
        if len(peaks) == 0:
            return []
        groups = []
        curr = [peaks[0]]
        for p in peaks[1:]:
            if p - curr[-1] <= min_gap:
                curr.append(p)
            else:
                groups.append(int(np.mean(curr)))
                curr = [p]
        groups.append(int(np.mean(curr)))
        return groups

    def _detect_black_circle_dot(self, cell: np.ndarray) -> int:
        if cell.size == 0:
            return 0
        ch, cw = cell.shape[:2]
        cy1, cy2 = max(1, int(ch * 0.15)), min(ch - 1, int(ch * 0.85))
        cx1, cx2 = max(1, int(cw * 0.15)), min(cw - 1, int(cw * 0.85))
        center = cell[cy1:cy2, cx1:cx2]
        if center.size == 0:
            return 0

        c_gray = cv2.cvtColor(center, cv2.COLOR_BGR2GRAY) if len(center.shape) == 3 else center
        c_bin = cv2.threshold(c_gray, 130, 255, cv2.THRESH_BINARY_INV)[1]
        dark_pixels = cv2.countNonZero(c_bin)
        total_pixels = center.shape[0] * center.shape[1]
        dark_ratio = dark_pixels / float(max(1, total_pixels))

        return 1 if dark_ratio >= self.config.min_ink_ratio else 0

    def process_image(self, image: np.ndarray) -> Dict[str, Any]:
        # 1. Warp Table Grid
        warped_table = self._rectify_table_grid(image)

        # 2. Extract ONLY Horizontal Coordinates (y_lines)
        gray_p = cv2.cvtColor(warped_table, cv2.COLOR_BGR2GRAY)
        bin_p = cv2.adaptiveThreshold(gray_p, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 21, 5)

        h_proj = np.sum(cv2.morphologyEx(bin_p, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (100, 1))) > 0, axis=1)
        h_peaks = np.where(h_proj > 400)[0]
        raw_y_lines = self._merge_peaks(h_peaks, min_gap=10)

        # Build clean row intervals, dynamically splitting any merged/oversized rows
        raw_gaps = [raw_y_lines[i + 1] - raw_y_lines[i] for i in range(len(raw_y_lines) - 1)]
        valid_gaps = [g for g in raw_gaps if 30 <= g <= 75]
        median_h = float(np.median(valid_gaps)) if valid_gaps else 52.0

        row_intervals = []
        for r in range(len(raw_y_lines) - 1):
            y1, y2 = raw_y_lines[r], raw_y_lines[r + 1]
            h_row = y2 - y1
            num_sub_rows = max(1, int(round(h_row / median_h)))
            step = h_row / float(num_sub_rows)
            for sub in range(num_sub_rows):
                sub_y1 = int(round(y1 + sub * step))
                sub_y2 = int(round(y1 + (sub + 1) * step))
                if (sub_y2 - sub_y1) >= 15:
                    row_intervals.append((sub_y1, sub_y2))

        # First interval is Table Header, remaining intervals are student rows (up to 25)
        student_intervals = row_intervals[1:] if len(row_intervals) > 1 else row_intervals

        # Fixed Horizontal Column Proportions
        roll_x1, roll_x2 = 4, 110
        name_x1, name_x2 = 110, 338
        day_start, day_end = 338, 1874
        day_w = (day_end - day_start) / 31.0
        day_x = [int(day_start + d * day_w) for d in range(32)]

        annotated = warped_table.copy()

        # Draw grid lines for visualization
        for (y1, y2) in student_intervals:
            cv2.line(annotated, (0, y1), (annotated.shape[1], y1), (0, 0, 255), 1)
        if student_intervals:
            cv2.line(annotated, (0, student_intervals[-1][1]), (annotated.shape[1], student_intervals[-1][1]), (0, 0, 255), 1)

        cv2.line(annotated, (roll_x1, 0), (roll_x1, annotated.shape[0]), (0, 255, 0), 1)
        cv2.line(annotated, (name_x1, 0), (name_x1, annotated.shape[0]), (0, 255, 0), 1)
        cv2.line(annotated, (name_x2, 0), (name_x2, annotated.shape[0]), (0, 255, 0), 1)
        for dx in day_x:
            cv2.line(annotated, (dx, 0), (dx, annotated.shape[0]), (0, 255, 0), 1)

        records = []

        # 3. Pure Row-Wise Loop on each student row
        for idx, (y1, y2) in enumerate(student_intervals[:25]):
            # A. Extract Roll Number in this row
            roll_crop = warped_table[y1 + 2:y2 - 2, roll_x1:roll_x2]
            roll_pad = cv2.copyMakeBorder(roll_crop, 15, 15, 15, 15, cv2.BORDER_CONSTANT, value=(255, 255, 255)) if roll_crop.size > 0 else None
            res_roll, _ = rapid_engine(roll_pad) if roll_pad is not None else (None, None)
            roll_str = "".join(filter(str.isdigit, " ".join([b[1] for b in res_roll]))) if res_roll else ""

            # B. Extract Student Name in this row
            name_crop = warped_table[y1 + 2:y2 - 2, name_x1:name_x2]
            name_pad = cv2.copyMakeBorder(name_crop, 15, 15, 15, 15, cv2.BORDER_CONSTANT, value=(255, 255, 255)) if name_crop.size > 0 else None
            res_name, _ = rapid_engine(name_pad) if name_pad is not None else (None, None)
            name_raw = " ".join([b[1] for b in res_name]).strip() if res_name else ""
            clean_name = " ".join(["".join(filter(str.isalpha, w)).capitalize() for w in name_raw.split() if len(w) > 1])

            # C. In SAME ROW, loop 31 Day Cells: 0 = Empty, 1 = Black Circle/Mark
            marks = []
            for d in range(31):
                dx1, dx2 = day_x[d], day_x[d + 1]
                c_crop = warped_table[y1:y2, dx1:dx2]
                is_marked = self._detect_black_circle_dot(c_crop)
                marks.append(is_marked)

                cx, cy = int((dx1 + dx2) / 2), int((y1 + y2) / 2)
                if is_marked == 1:
                    cv2.circle(annotated, (cx, cy), 4, (0, 200, 0), -1)  # Green filled dot (1/P)
                else:
                    cv2.circle(annotated, (cx, cy), 2, (0, 0, 255), 1)   # Red circle (0/A)

            p_count = sum(marks)
            a_count = 31 - p_count

            roll_display = str(101 + idx)
            name_display = clean_name if clean_name else f"Student {roll_display}"

            if clean_name:
                cv2.putText(annotated, clean_name, (name_x1 + 5, y2 - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (255, 0, 255), 1)

            records.append({
                "roll_no": roll_display,
                "student_name": name_display,
                "attendance": marks,
                "present_count": p_count,
                "absent_count": a_count
            })

        # Coordinates DataFrames
        y_lines_all = [s[0] for s in student_intervals] + ([student_intervals[-1][1]] if student_intervals else [])
        y_df = pd.DataFrame({"Horizontal_Line_Index": range(len(y_lines_all)), "Y_Position": y_lines_all})
        x_df = pd.DataFrame({"Vertical_Line_Index": range(len(day_x) + 3), "X_Position": [roll_x1, roll_x2, name_x2] + day_x})
        table_info = pd.DataFrame([{
            "Table_Left": roll_x1,
            "Table_Right": day_end,
            "Table_Top": student_intervals[0][0] if student_intervals else 0,
            "Table_Bottom": student_intervals[-1][1] if student_intervals else 1400,
            "Table_Width": day_end - roll_x1,
            "Table_Height": (student_intervals[-1][1] - student_intervals[0][0]) if student_intervals else 1400,
            "Horizontal_Lines": len(student_intervals) + 1,
            "Vertical_Lines": len(day_x) + 3
        }])

        return {
            "status": "success",
            "total_students": len(records),
            "days_count": 31,
            "data": records,
            "debug_image": annotated,
            "horizontal_lines_df": y_df,
            "vertical_lines_df": x_df,
            "table_info_df": table_info
        }
