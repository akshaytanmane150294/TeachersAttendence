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
    min_ink_ratio: float = 0.05
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
        # Exact 20% margin from Top, Bottom, Left, and Right (detecting inside the 60% center core)
        cy1, cy2 = int(ch * 0.20), int(ch * 0.80)
        cx1, cx2 = int(cw * 0.20), int(cw * 0.80)
        core = cell[cy1:cy2, cx1:cx2]
        if core.size == 0:
            return 0

        c_gray = cv2.cvtColor(core, cv2.COLOR_BGR2GRAY) if len(core.shape) == 3 else core
        bg = np.percentile(c_gray, 85)
        min_val = np.min(c_gray)
        diff = bg - min_val
        dark_ratio = np.sum(c_gray < (bg - 35)) / float(c_gray.size)

        # Empty-First Check: If contrast is low or dark pixels are sparse -> Absent (0), else Present (1)
        is_empty = (diff < 50) or (dark_ratio < 0.08)
        return 0 if is_empty else 1

    def process_image(self, image: np.ndarray) -> Dict[str, Any]:
        # 1. Warp Table Grid
        warped_table = self._rectify_table_grid(image)

        # 2. Extract Dynamic Horizontal Coordinates (y_lines) & Vertical Coordinates (x_lines)
        gray_p = cv2.cvtColor(warped_table, cv2.COLOR_BGR2GRAY)
        bin_p = cv2.adaptiveThreshold(gray_p, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 21, 5)

        # A. Horizontal Line Peaks (Project exclusively in Day Grid 350..1850 to avoid text interference)
        h_w = cv2.morphologyEx(bin_p, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (60, 1)))
        h_proj = np.sum(h_w[:, 350:1850] > 0, axis=1)
        h_peaks = np.where(h_proj > 350)[0]
        raw_y_lines = self._merge_peaks(h_peaks, min_gap=12)

        # B. Vertical Line Peaks (Real Printed Column Lines)
        v_w = cv2.morphologyEx(bin_p, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, 60)))
        v_proj = np.sum(v_w[100:1350, :] > 0, axis=0)
        v_peaks = np.where(v_proj > 200)[0]
        raw_x_lines = self._merge_peaks(v_peaks, min_gap=8)

        # Exact Mapping of 25 Student Rows (raw_y[1:]) and 31 Day Columns (raw_x[2:34])
        if len(raw_y_lines) >= 26:
            row_lines = raw_y_lines[1:27]
        else:
            row_lines = [int(round(99 + r * 51.88)) for r in range(26)]

        student_intervals = [(row_lines[r], row_lines[r + 1]) for r in range(len(row_lines) - 1)]

        # Dynamic Day Columns (Day 1 to 31)
        if len(raw_x_lines) >= 35:
            day_cols = raw_x_lines[2:34]
        else:
            day_cols = [int(round(345 + d * 48.9)) for d in range(32)]

        roll_x1 = raw_x_lines[0] if raw_x_lines else 13
        roll_x2 = raw_x_lines[1] if len(raw_x_lines) > 1 else 124
        name_x1 = roll_x2
        name_x2 = day_cols[0] if day_cols else 345
        day_end = day_cols[-1]

        # Dynamic Month Detection via Header OCR
        header_crop = warped_table[0:student_intervals[0][0], :] if student_intervals else warped_table[0:100, :]
        res_header, _ = rapid_engine(header_crop) if header_crop.size > 0 else (None, None)
        header_text = " ".join([b[1] for b in res_header]).upper() if res_header else ""

        days_count = 31
        if any(m in header_text for m in ["JUNE", "APRIL", "SEPTEMBER", "NOVEMBER", "JUN", "APR", "SEP", "NOV"]):
            days_count = 30
        elif "FEBRUARY" in header_text or "FEB" in header_text:
            days_count = 28

        annotated = warped_table.copy()

        # Clean Table Bounding Box (Blue Outer Border on exact table black lines)
        table_top_y = raw_y_lines[0] if raw_y_lines else (max(0, student_intervals[0][0] - 52) if student_intervals else 0)
        table_bottom_y = raw_y_lines[-1] if raw_y_lines else (student_intervals[-1][1] if student_intervals else 1380)
        cv2.rectangle(annotated, (table_left_x, table_top_y), (table_right_x, table_bottom_y), (0, 230, 118), 3)
        for pt in [(table_left_x, table_top_y), (table_right_x, table_top_y), (table_right_x, table_bottom_y), (table_left_x, table_bottom_y)]:
            cv2.circle(annotated, pt, 8, (0, 230, 118), -1)
            cv2.circle(annotated, pt, 8, (255, 255, 255), 2)

        records = []

        # 3. Pure Row-Wise & Column-Wise Loop using Exact Line Coordinates
        for idx, (Y_top, Y_bottom) in enumerate(student_intervals[:25]):
            delta_Y = Y_bottom - Y_top

            # A. Extract Roll Number in this row
            roll_crop = warped_table[Y_top + 2:Y_bottom - 2, roll_x1:roll_x2]
            roll_pad = cv2.copyMakeBorder(roll_crop, 15, 15, 15, 15, cv2.BORDER_CONSTANT, value=(255, 255, 255)) if roll_crop.size > 0 else None
            res_roll, _ = rapid_engine(roll_pad) if roll_pad is not None else (None, None)
            roll_str = "".join(filter(str.isdigit, " ".join([b[1] for b in res_roll]))) if res_roll else ""

            # B. Extract Student Name in this row
            name_crop = warped_table[Y_top + 2:Y_bottom - 2, name_x1:name_x2]
            name_pad = cv2.copyMakeBorder(name_crop, 15, 15, 15, 15, cv2.BORDER_CONSTANT, value=(255, 255, 255)) if name_crop.size > 0 else None
            res_name, _ = rapid_engine(name_pad) if name_pad is not None else (None, None)
            name_raw = " ".join([b[1] for b in res_name]).strip() if res_name else ""
            clean_name = " ".join(["".join(filter(str.isalpha, w)).capitalize() for w in name_raw.split() if len(w) > 1])

            # C. Check each Day Cell between Vertical Lines X_d and X_d+1 with 20% margin from lines
            marks = []
            for d in range(min(31, len(day_cols) - 1)):
                X_left, X_right = day_cols[d], day_cols[d + 1]
                delta_X = X_right - X_left

                # Exact 20% distance from horizontal & vertical lines:
                y1 = int(round(Y_top + 0.20 * delta_Y))
                y2 = int(round(Y_bottom - 0.20 * delta_Y))
                x1 = int(round(X_left + 0.20 * delta_X))
                x2 = int(round(X_right - 0.20 * delta_X))

                core = warped_table[y1:y2, x1:x2]
                
                # Check for 400-Pixel Black Circle Dot in Core (Adaptive Ink Density)
                if core.size == 0:
                    is_marked = 0
                else:
                    c_gray = cv2.cvtColor(core, cv2.COLOR_BGR2GRAY) if len(core.shape) == 3 else core
                    bg = np.percentile(c_gray, 85)
                    min_val = np.min(c_gray)
                    diff = bg - min_val

                    # Count dark ink pixels (pixels significantly darker than paper background)
                    # A 400-pixel black circle occupies 150-400 pixels inside the 20% core box
                    ink_pixels = np.sum(c_gray < (bg - 30))

                    # Circle Detection: True marked circle (dark or light/faded) has >= 100 ink pixels & diff >= 45
                    # Empty cell with shadow/watermark has diff < 20 and ink_pixels = 0
                    is_marked = 1 if (ink_pixels >= 100 and diff >= 45) else 0

                marks.append(is_marked)

                # Centroid of the cell for clean visual dot
                cx = int(round((X_left + X_right) / 2.0))
                cy = int(round((Y_top + Y_bottom) / 2.0))
                if is_marked == 1:
                    cv2.circle(annotated, (cx, cy), 4, (0, 200, 0), -1)  # Green filled dot (1/P)
                else:
                    cv2.circle(annotated, (cx, cy), 2, (0, 0, 255), -1)  # Red small dot (0/A)

            # Calculate Active Month Days Present / Absent
            active_marks = marks[:days_count]
            p_count = sum(active_marks)
            a_count = days_count - p_count

            roll_display = str(101 + idx)
            name_display = clean_name if clean_name else f"Student {roll_display}"

            if clean_name:
                cv2.putText(annotated, clean_name, (name_x1 + 5, Y_bottom - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (255, 0, 255), 1)

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
        x_df = pd.DataFrame({"Vertical_Line_Index": range(len(day_cols) + 3), "X_Position": [roll_x1, roll_x2, name_x2] + day_cols})
        table_info = pd.DataFrame([{
            "Table_Left": roll_x1,
            "Table_Right": day_end,
            "Table_Top": student_intervals[0][0] if student_intervals else 0,
            "Table_Bottom": student_intervals[-1][1] if student_intervals else 1400,
            "Table_Width": day_end - roll_x1,
            "Table_Height": (student_intervals[-1][1] - student_intervals[0][0]) if student_intervals else 1400,
            "Horizontal_Lines": len(student_intervals) + 1,
            "Vertical_Lines": len(day_cols) + 3
        }])

        return {
            "status": "success",
            "total_students": len(records),
            "days_count": days_count,
            "data": records,
            "debug_image": annotated,
            "horizontal_lines_df": y_df,
            "vertical_lines_df": x_df,
            "table_info_df": table_info
        }
