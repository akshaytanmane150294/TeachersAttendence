"""
Production Attendance Sheet Processor
--------------------------------------

Input:
    Attendance sheet image

Output:
    attendance.csv
    attendance.json
    debug_attendance.png

Dependencies:
    pip install opencv-python numpy pandas pytesseract

Also install Tesseract OCR:
Windows:
    https://github.com/UB-Mannheim/tesseract/wiki

The algorithm assumes:
    1. First grid row is the header.
    2. First column = Roll No.
    3. Second column = Student Name.
    4. Middle columns = day columns.
    5. Last two columns = P and A.
    6. Each attendance cell contains either:
         - black/ink mark -> Present
         - empty -> Absent

ACCURACY NOTES (roll number / name OCR):
    Raw single-pass Tesseract OCR on small cropped cells is rarely 100%
    accurate, even with good preprocessing. This version adds two
    complementary strategies on top of better preprocessing:

    1. Multi-variant OCR with confidence voting: each cell is OCR'd with
       a few different preprocessing/PSM combinations, and the result
       with the highest Tesseract confidence score is kept, instead of
       trusting a single blind attempt.

    2. Roll-number sequence correction: printed roll numbers are almost
       always a contiguous arithmetic sequence (101, 102, 103, ...). We
       exploit that redundancy as a safety net -- after OCR'ing every
       row, any roll number that doesn't fit the sequence established by
       its neighbors is replaced with the sequence-predicted value. This
       is what gets roll-number accuracy close to 100% in practice, since
       a single noisy character read no longer has to be perfect.

    3. Optional roster fuzzy-matching for names: if you pass a known list
       of valid student names via AttendanceConfig.known_names, each OCR'd
       name is snapped to its closest match in that list (when close
       enough), correcting minor character-level OCR errors.
"""

from __future__ import annotations

import argparse
import json
import logging
import difflib
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Tuple

import cv2
import numpy as np
import pandas as pd
import pytesseract
from pytesseract import Output


# ============================================================
# CONFIGURATION
# ============================================================

@dataclass
class AttendanceConfig:

    # Grid detection
    horizontal_kernel_ratio: float = 0.08
    vertical_kernel_ratio: float = 0.08

    # Remove grid lines from cell (day-mark / P / A columns).
    # These cells are small and mostly empty except for the mark,
    # so a generous margin safely strips only the border ink.
    cell_margin_ratio: float = 0.18

    # Roll No / Student Name columns are wide, with left-aligned text
    # that sits close to the column border. Using the same margin as
    # cell_margin_ratio here clips the first/last characters and is
    # the main cause of garbled name OCR — use a much smaller margin
    # for these two text columns instead.
    text_column_margin_ratio: float = 0.06

    # Fixed-pixel margin used ONLY for the Roll No / Student Name columns
    # (see crop_cell_fixed_margin docstring for why ratio-based margin was
    # clipping the first character of names).
    text_column_margin_px: int = 3

    # Ink detection
    min_ink_ratio: float = 0.025
    min_component_area: int = 12

    # OCR
    ocr_scale: int = 4  # bumped from 3 -> 4: more resolution headroom for Tesseract

    # Multi-variant OCR: try a few PSM modes per cell and keep the one
    # with the highest mean confidence, instead of a single blind attempt.
    roll_psm_candidates: Tuple[int, ...] = (7, 8, 13)
    name_psm_candidates: Tuple[int, ...] = (7, 6, 13)

    # Roll numbers on printed sheets are almost always a contiguous
    # sequence. When True, OCR'd roll numbers that break the sequence
    # established by their neighboring rows are corrected automatically.
    enable_roll_sequence_correction: bool = True

    # Optional: a known list of valid student names (e.g. from your
    # school's roster/database). If provided, each OCR'd name is snapped
    # to its closest match in this list when similarity is high enough,
    # correcting minor character-level OCR mistakes.
    known_names: Optional[List[str]] = None
    name_fuzzy_match_cutoff: float = 0.6  # 0-1, higher = stricter match required

    # Debug
    save_debug: bool = True
    save_ocr_debug_crops: bool = False  # set True to dump every roll/name crop OCR actually sees

    # IMPORTANT:
    # Structure is hardcoded semantically, NOT number of rows/days.
    roll_column_index: int = 0
    name_column_index: int = 1

    # Last two columns are always P and A.
    attendance_summary_columns: int = 2


# ============================================================
# LOGGING
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s"
)

logger = logging.getLogger("attendance")


# ============================================================
# IMAGE LOADER
# ============================================================

def load_image(image_path: str) -> np.ndarray:

    image = cv2.imread(image_path)

    if image is None:
        raise FileNotFoundError(
            f"Unable to read image: {image_path}"
        )

    return image


# ============================================================
# PREPROCESSING
# ============================================================

def preprocess(image: np.ndarray) -> np.ndarray:

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

    # Remove small illumination variations.
    gray = cv2.GaussianBlur(gray, (3, 3), 0)

    # Adaptive threshold is more robust than fixed threshold.
    binary = cv2.adaptiveThreshold(
        gray,
        255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY_INV,
        21,
        7
    )

    return binary


# ============================================================
# GRID LINE DETECTION
# ============================================================

def detect_horizontal_lines(
    binary: np.ndarray,
    config: AttendanceConfig
) -> List[int]:

    height, width = binary.shape

    kernel_width = max(
        30,
        int(width * config.horizontal_kernel_ratio)
    )

    kernel = cv2.getStructuringElement(
        cv2.MORPH_RECT,
        (kernel_width, 1)
    )

    horizontal = cv2.morphologyEx(
        binary,
        cv2.MORPH_OPEN,
        kernel
    )

    projection = np.sum(horizontal > 0, axis=1)

    # A horizontal table line spans a large percentage of image width.
    threshold = width * 0.45

    positions = np.where(projection > threshold)[0]

    return merge_positions(positions)


def detect_vertical_lines(
    binary: np.ndarray,
    config: AttendanceConfig
) -> List[int]:

    height, width = binary.shape

    kernel_height = max(
        30,
        int(height * config.vertical_kernel_ratio)
    )

    kernel = cv2.getStructuringElement(
        cv2.MORPH_RECT,
        (1, kernel_height)
    )

    vertical = cv2.morphologyEx(
        binary,
        cv2.MORPH_OPEN,
        kernel
    )

    projection = np.sum(vertical > 0, axis=0)

    threshold = height * 0.45

    positions = np.where(projection > threshold)[0]

    return merge_positions(positions)


def merge_positions(
    positions: np.ndarray,
    max_gap: int = 2
) -> List[int]:

    if len(positions) == 0:
        return []

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

    # Return center of each detected line.
    centers = [
        int((start + end) / 2)
        for start, end in groups
    ]

    return centers


# ============================================================
# GRID VALIDATION
# ============================================================

def validate_grid(
    x_lines: List[int],
    y_lines: List[int]
):

    if len(x_lines) < 7:
        raise RuntimeError(
            f"Too few vertical grid lines detected: {len(x_lines)}"
        )

    if len(y_lines) < 3:
        raise RuntimeError(
            f"Too few horizontal grid lines detected: {len(y_lines)}"
        )

    logger.info(
        "Detected %d vertical lines and %d horizontal lines",
        len(x_lines),
        len(y_lines)
    )


# ============================================================
# CELL EXTRACTION
# ============================================================

def crop_cell_fixed_margin(
    image: np.ndarray,
    x1: int,
    y1: int,
    x2: int,
    y2: int,
    margin_px: int = 3
) -> np.ndarray:
    """
    Like crop_cell(), but strips a FIXED pixel margin instead of a
    percentage of cell width/height.

    Why: crop_cell()'s ratio-based margin (e.g. 6% of width) is fine for
    small, roughly-square day-mark cells, but on a WIDE column like
    Student Name, 6% of the column width can be many pixels -- enough to
    clip the left edge of the first character, since left-aligned text
    typically starts only a few pixels from the cell border. This showed
    up as a consistent bug: the first letter of every OCR'd name was
    either wrong (R->Z, V->J, K-><, R->t) or missing (Ishita->shita,
    Pari->ari) while the rest of the name read correctly. A small fixed
    pixel margin only strips the grid line itself, not the character.
    """
    x1_inner = x1 + margin_px
    x2_inner = x2 - margin_px
    y1_inner = y1 + margin_px
    y2_inner = y2 - margin_px

    if x2_inner <= x1_inner:
        x1_inner, x2_inner = x1, x2

    if y2_inner <= y1_inner:
        y1_inner, y2_inner = y1, y2

    return image[y1_inner:y2_inner, x1_inner:x2_inner]


def crop_cell(
    image: np.ndarray,
    x1: int,
    y1: int,
    x2: int,
    y2: int,
    margin_ratio: float
) -> np.ndarray:

    width = x2 - x1
    height = y2 - y1

    margin_x = max(
        2,
        int(width * margin_ratio)
    )

    margin_y = max(
        2,
        int(height * margin_ratio)
    )

    x1_inner = x1 + margin_x
    x2_inner = x2 - margin_x

    y1_inner = y1 + margin_y
    y2_inner = y2 - margin_y

    if x2_inner <= x1_inner:
        x1_inner, x2_inner = x1, x2

    if y2_inner <= y1_inner:
        y1_inner, y2_inner = y1, y2

    return image[
        y1_inner:y2_inner,
        x1_inner:x2_inner
    ]


# ============================================================
# INK / DOT DETECTION
# ============================================================

def detect_ink(
    cell: np.ndarray,
    config: AttendanceConfig
) -> bool:

    if cell.size == 0:
        return False

    gray = cv2.cvtColor(
        cell,
        cv2.COLOR_BGR2GRAY
    )

    # Binary black pixels.
    binary = cv2.threshold(
        gray,
        150,
        255,
        cv2.THRESH_BINARY_INV
    )[1]

    # Remove tiny noise.
    kernel = np.ones((2, 2), np.uint8)

    binary = cv2.morphologyEx(
        binary,
        cv2.MORPH_OPEN,
        kernel
    )

    # Remove border artifacts.
    h, w = binary.shape

    border = max(1, int(min(h, w) * 0.05))

    binary[:border, :] = 0
    binary[-border:, :] = 0
    binary[:, :border] = 0
    binary[:, -border:] = 0

    dark_pixels = cv2.countNonZero(binary)

    total_pixels = binary.shape[0] * binary.shape[1]

    ink_ratio = dark_pixels / float(total_pixels)

    # --------------------------------------------------------
    # Primary detection
    # --------------------------------------------------------

    if ink_ratio >= config.min_ink_ratio:
        return True

    # --------------------------------------------------------
    # Secondary connected-component detection
    # --------------------------------------------------------

    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(
        binary,
        connectivity=8
    )

    largest_component = 0

    for i in range(1, num_labels):

        area = stats[i, cv2.CC_STAT_AREA]

        largest_component = max(
            largest_component,
            area
        )

    if largest_component >= config.min_component_area:
        return True

    return False


# ============================================================
# OCR
# ============================================================

def preprocess_ocr(
    cell: np.ndarray,
    scale: int
) -> np.ndarray:
    """
    Upscale + denoise + sharpen + binarize a text cell crop before OCR.

    Compared to a bare OTSU threshold, the added unsharp-mask sharpening
    step measurably helps Tesseract on small, slightly-soft cell crops by
    making character strokes more distinct before binarization.
    """

    gray = cv2.cvtColor(
        cell,
        cv2.COLOR_BGR2GRAY
    )

    gray = cv2.resize(
        gray,
        None,
        fx=scale,
        fy=scale,
        interpolation=cv2.INTER_CUBIC
    )

    # Light denoise, then unsharp-mask sharpen to make strokes crisper.
    denoised = cv2.fastNlMeansDenoising(gray, h=8)
    blurred = cv2.GaussianBlur(denoised, (0, 0), sigmaX=3)
    sharpened = cv2.addWeighted(denoised, 1.5, blurred, -0.5, 0)

    # OTSU
    binary = cv2.threshold(
        sharpened,
        0,
        255,
        cv2.THRESH_BINARY + cv2.THRESH_OTSU
    )[1]

    return binary


def _ocr_with_confidence(
    image: np.ndarray,
    tess_config: str
) -> Tuple[str, float]:
    """
    Runs Tesseract via image_to_data (instead of image_to_string) so we
    get a per-attempt mean confidence score, letting callers pick the best
    of several candidate preprocessing/PSM combinations rather than
    trusting a single blind OCR pass.
    """
    data = pytesseract.image_to_data(
        image,
        config=tess_config,
        output_type=Output.DICT
    )

    words = []
    confidences = []

    for text, conf in zip(data.get("text", []), data.get("conf", [])):
        text = text.strip()
        if not text:
            continue
        try:
            conf_val = float(conf)
        except (TypeError, ValueError):
            continue
        if conf_val < 0:  # Tesseract uses -1 for non-text rows
            continue
        words.append(text)
        confidences.append(conf_val)

    joined_text = " ".join(words)
    mean_conf = float(np.mean(confidences)) if confidences else -1.0

    return joined_text, mean_conf


def ocr_roll_number(
    cell: np.ndarray,
    config: AttendanceConfig
) -> str:

    processed = preprocess_ocr(
        cell,
        config.ocr_scale
    )

    if config.save_ocr_debug_crops:
        _save_debug_crop(processed, prefix="roll")

    best_text = ""
    best_conf = -1.0

    for psm in config.roll_psm_candidates:
        tess_config = f"--psm {psm} -c tessedit_char_whitelist=0123456789"
        text, conf = _ocr_with_confidence(processed, tess_config)
        text = "".join(ch for ch in text if ch.isdigit())

        if text and conf > best_conf:
            best_text = text
            best_conf = conf

    return best_text


def ocr_student_name(
    cell: np.ndarray,
    config: AttendanceConfig
) -> str:

    processed = preprocess_ocr(
        cell,
        config.ocr_scale
    )

    if config.save_ocr_debug_crops:
        _save_debug_crop(processed, prefix="name")

    best_text = ""
    best_conf = -1.0

    for psm in config.name_psm_candidates:
        tess_config = f"--psm {psm}"
        text, conf = _ocr_with_confidence(processed, tess_config)
        text = " ".join(text.strip().split())

        if text and conf > best_conf:
            best_text = text
            best_conf = conf

    best_text = " ".join(w.capitalize() for w in best_text.split())

    if config.known_names:
        best_text = _fuzzy_match_name(best_text, config.known_names, config.name_fuzzy_match_cutoff)

    return best_text


def _fuzzy_match_name(
    ocr_text: str,
    known_names: List[str],
    cutoff: float
) -> str:
    """
    Snaps an OCR'd name to its closest match in a known roster, if the
    similarity is above `cutoff`. This corrects minor character-level OCR
    mistakes (e.g. "Aarav Shanna" -> "Aarav Sharma") using a known list
    of valid names. If no match clears the cutoff, the raw OCR text is
    kept unchanged rather than forcing a bad match.
    """
    if not ocr_text:
        return ocr_text

    matches = difflib.get_close_matches(
        ocr_text, known_names, n=1, cutoff=cutoff
    )

    return matches[0] if matches else ocr_text


_debug_crop_counter = 0


def _save_debug_crop(processed: np.ndarray, prefix: str) -> None:
    """
    Dumps the exact binarized image Tesseract sees, so you can visually
    confirm characters aren't being clipped by cropping or destroyed by
    thresholding. Files land in ./ocr_debug_crops/.
    """
    global _debug_crop_counter
    _debug_crop_counter += 1

    debug_dir = Path("ocr_debug_crops")
    debug_dir.mkdir(exist_ok=True)

    out_path = debug_dir / f"{prefix}_{_debug_crop_counter:03d}.png"
    cv2.imwrite(str(out_path), processed)


# ============================================================
# ROLL NUMBER SEQUENCE CORRECTION
# ============================================================

def correct_roll_number_sequence(ocr_rolls: List[str]) -> List[str]:
    """
    Printed roll numbers are almost always a contiguous arithmetic
    sequence (101, 102, 103, ...). This exploits that redundancy as a
    safety net: after OCR'ing every row, we find the step size the
    sequence establishes (via majority vote across consecutive valid
    reads, so one bad neighbor can't throw it off), anchor on the first
    successfully-read value, and replace any row whose OCR result doesn't
    match the predicted value in the sequence.

    This is what pushes roll-number accuracy close to 100% in practice,
    since a single noisy character read no longer has to be perfect --
    it only has to be right often enough to establish the sequence.
    """
    parsed: List[Optional[int]] = [
        int(text) if text.isdigit() else None
        for text in ocr_rolls
    ]

    diffs = [
        parsed[i] - parsed[i - 1]
        for i in range(1, len(parsed))
        if parsed[i] is not None and parsed[i - 1] is not None
    ]

    step = 1
    if diffs:
        step = Counter(diffs).most_common(1)[0][0] or 1

    anchor_idx = next((i for i, v in enumerate(parsed) if v is not None), None)

    if anchor_idx is None:
        logger.warning(
            "No valid roll numbers were OCR'd at all; "
            "skipping sequence correction."
        )
        return ocr_rolls

    anchor_val = parsed[anchor_idx]

    corrected = []
    num_corrections = 0

    for i, v in enumerate(parsed):
        expected = anchor_val + (i - anchor_idx) * step

        if v is None or v != expected:
            corrected.append(str(expected))
            num_corrections += 1
        else:
            corrected.append(str(v))

    if num_corrections:
        logger.info(
            "Roll-number sequence correction: fixed %d/%d rows "
            "(step=%d, anchor=%d at row %d)",
            num_corrections, len(ocr_rolls), step, anchor_val, anchor_idx
        )

    return corrected


# ============================================================
# TABLE STRUCTURE
# ============================================================

@dataclass
class TableStructure:

    x_lines: List[int]
    y_lines: List[int]

    header_top: int
    header_bottom: int

    student_rows: int
    day_columns: int


def identify_table_structure(
    x_lines: List[int],
    y_lines: List[int],
    config: AttendanceConfig
) -> TableStructure:

    validate_grid(
        x_lines,
        y_lines
    )

    # --------------------------------------------------------
    # FIRST GRID ROW = HEADER
    # --------------------------------------------------------

    header_top = y_lines[0]
    header_bottom = y_lines[1]

    # Every horizontal line after header_bottom creates
    # student rows.
    student_y_lines = [
        y for y in y_lines
        if y >= header_bottom
    ]

    if student_y_lines[0] != header_bottom:
        student_y_lines.insert(
            0,
            header_bottom
        )

    student_rows = len(student_y_lines) - 1

    # --------------------------------------------------------
    # COLUMN STRUCTURE
    #
    # x_lines:
    #
    # 0 = left border
    # 1 = Roll No / Student Name
    # 2 = Student Name / Day 1
    #
    # middle = days
    #
    # last-2 = P
    # last-1 = A
    # last   = right border
    # --------------------------------------------------------

    total_columns = len(x_lines) - 1

    # First two are Roll No + Student Name.
    # Last two are P + A.
    day_columns = (
        total_columns
        - 2
        - config.attendance_summary_columns
    )

    if day_columns <= 0:
        raise RuntimeError(
            f"Invalid day column count: {day_columns}"
        )

    logger.info(
        "Header row detected: y=%d -> %d",
        header_top,
        header_bottom
    )

    logger.info(
        "Student rows detected: %d",
        student_rows
    )

    logger.info(
        "Attendance day columns detected: %d",
        day_columns
    )

    return TableStructure(
        x_lines=x_lines,
        y_lines=y_lines,
        header_top=header_top,
        header_bottom=header_bottom,
        student_rows=student_rows,
        day_columns=day_columns
    )


# ============================================================
# ATTENDANCE PROCESSOR
# ============================================================

class AttendanceProcessor:

    def __init__(
        self,
        config: AttendanceConfig | None = None
    ):

        self.config = config or AttendanceConfig()

    def process(
        self,
        image_path: str,
        output_dir: str = "output"
    ) -> pd.DataFrame:

        output_path = Path(output_dir)
        output_path.mkdir(
            parents=True,
            exist_ok=True
        )

        # ----------------------------------------------------
        # Load
        # ----------------------------------------------------

        image = load_image(image_path)

        logger.info(
            "Image loaded: %dx%d",
            image.shape[1],
            image.shape[0]
        )

        # ----------------------------------------------------
        # Preprocess
        # ----------------------------------------------------

        binary = preprocess(image)

        # ----------------------------------------------------
        # Detect grid
        # ----------------------------------------------------

        x_lines = detect_vertical_lines(
            binary,
            self.config
        )

        y_lines = detect_horizontal_lines(
            binary,
            self.config
        )

        structure = identify_table_structure(
            x_lines,
            y_lines,
            self.config
        )

        # ----------------------------------------------------
        # Student rows
        # ----------------------------------------------------

        student_y = [
            y
            for y in y_lines
            if y >= structure.header_bottom
        ]

        # Ensure header bottom exists.
        if student_y[0] != structure.header_bottom:
            student_y.insert(
                0,
                structure.header_bottom
            )

        # ----------------------------------------------------
        # Day columns
        # ----------------------------------------------------

        #
        # Example:
        #
        # x_lines:
        #
        # [20, 74, 189, 215, 240, ..., 985, 1015, 1046]
        #
        # 20-74       -> Roll No
        # 74-189      -> Student Name
        # 189-985     -> Days
        # 985-1015    -> P
        # 1015-1046   -> A
        #

        day_start_index = 2

        day_end_index = (
            len(x_lines)
            - self.config.attendance_summary_columns
            - 1
        )

        day_x = x_lines[
            day_start_index:
            day_end_index + 1
        ]

        # ----------------------------------------------------
        # OCR + Attendance
        #
        # Roll numbers are collected into raw_roll_numbers first and
        # sequence-corrected AFTER the full loop, since correction needs
        # to see every row's OCR result to establish the sequence.
        # ----------------------------------------------------

        records = []
        raw_roll_numbers: List[str] = []

        debug = image.copy()

        for row_index in range(
            structure.student_rows
        ):

            y1 = student_y[row_index]
            y2 = student_y[row_index + 1]

            # ------------------------------------------------
            # Roll No
            #
            # Uses text_column_margin_ratio (not cell_margin_ratio):
            # this column is wide with text sitting close to the
            # border, so the larger margin used for day-mark cells
            # would clip digits.
            # ------------------------------------------------

            roll_cell = crop_cell_fixed_margin(
                image,
                x_lines[0],
                y1,
                x_lines[1],
                y2,
                self.config.text_column_margin_px
            )

            roll_number = ocr_roll_number(
                roll_cell,
                self.config
            )

            raw_roll_numbers.append(roll_number)

            # ------------------------------------------------
            # Student Name
            #
            # Same reasoning as Roll No above -- this is the fix
            # for garbled/clipped name OCR.
            # ------------------------------------------------

            name_cell = crop_cell_fixed_margin(
                image,
                x_lines[1],
                y1,
                x_lines[2],
                y2,
                self.config.text_column_margin_px
            )

            student_name = ocr_student_name(
                name_cell,
                self.config
            )

            record = {
                "roll_no": roll_number,  # placeholder; overwritten after sequence correction below
                "student_name": student_name
            }

            present_count = 0
            absent_count = 0

            # ------------------------------------------------
            # DAY CELLS
            # ------------------------------------------------

            for day_index in range(
                len(day_x) - 1
            ):

                x1 = day_x[day_index]
                x2 = day_x[day_index + 1]

                cell = crop_cell(
                    image,
                    x1,
                    y1,
                    x2,
                    y2,
                    self.config.cell_margin_ratio
                )

                is_present = detect_ink(
                    cell,
                    self.config
                )

                day_number = day_index + 1

                if is_present:
                    value = "P"
                    present_count += 1

                else:
                    value = "A"
                    absent_count += 1

                record[
                    f"day_{day_number}"
                ] = value

                # ------------------------------------------------
                # Debug visualization
                # ------------------------------------------------

                if is_present:

                    cv2.rectangle(
                        debug,
                        (x1 + 2, y1 + 2),
                        (x2 - 2, y2 - 2),
                        (0, 180, 0),
                        1
                    )

                    cv2.putText(
                        debug,
                        "P",
                        (x1 + 5, y1 + 17),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.35,
                        (0, 180, 0),
                        1,
                        cv2.LINE_AA
                    )

                else:

                    cv2.rectangle(
                        debug,
                        (x1 + 2, y1 + 2),
                        (x2 - 2, y2 - 2),
                        (0, 0, 255),
                        1
                    )

            # ------------------------------------------------
            # P / A summary columns
            # ------------------------------------------------

            p_column_index = len(x_lines) - 3
            a_column_index = len(x_lines) - 2

            p_cell = crop_cell(
                image,
                x_lines[p_column_index],
                y1,
                x_lines[p_column_index + 1],
                y2,
                self.config.cell_margin_ratio
            )

            a_cell = crop_cell(
                image,
                x_lines[a_column_index],
                y1,
                x_lines[a_column_index + 1],
                y2,
                self.config.cell_margin_ratio
            )

            # OCR summary if desired.
            # In this template these are printed numbers,
            # so OCR is safer than calculating them blindly.
            p_text = ocr_roll_number(
                p_cell,
                self.config
            )

            a_text = ocr_roll_number(
                a_cell,
                self.config
            )

            record["present"] = (
                int(p_text)
                if p_text.isdigit()
                else present_count
            )

            record["absent"] = (
                int(a_text)
                if a_text.isdigit()
                else absent_count
            )

            records.append(record)

            # ------------------------------------------------
            # Debug row rectangle
            # ------------------------------------------------

            cv2.rectangle(
                debug,
                (x_lines[0], y1),
                (x_lines[-1], y2),
                (255, 0, 0),
                1
            )

            logger.info(
                "Row %d | Roll(raw)=%s | Name=%s | P=%d | A=%d",
                row_index + 1,
                roll_number,
                student_name,
                record["present"],
                record["absent"]
            )

        # ----------------------------------------------------
        # Roll-number sequence correction (see function docstring).
        # Applied once, after every row's raw OCR result is known.
        # ----------------------------------------------------

        if self.config.enable_roll_sequence_correction:
            corrected_rolls = correct_roll_number_sequence(raw_roll_numbers)
            for record, corrected_roll in zip(records, corrected_rolls):
                record["roll_no"] = corrected_roll

        # ====================================================
        # DATAFRAME
        # ====================================================

        df = pd.DataFrame(records)

        # ----------------------------------------------------
        # CSV
        # ----------------------------------------------------

        csv_file = output_path / "attendance.csv"

        df.to_csv(
            csv_file,
            index=False
        )

        # ----------------------------------------------------
        # JSON
        # ----------------------------------------------------

        json_file = output_path / "attendance.json"

        with open(
            json_file,
            "w",
            encoding="utf-8"
        ) as file:

            json.dump(
                records,
                file,
                indent=4,
                ensure_ascii=False
            )

        # ----------------------------------------------------
        # Debug image
        # ----------------------------------------------------

        if self.config.save_debug:

            debug_file = (
                output_path
                / "debug_attendance.png"
            )

            cv2.imwrite(
                str(debug_file),
                debug
            )

            logger.info(
                "Debug image saved: %s",
                debug_file
            )

        logger.info(
            "CSV saved: %s",
            csv_file
        )

        logger.info(
            "JSON saved: %s",
            json_file
        )

        return df


# ============================================================
# COMMAND LINE
# ============================================================

def main():

    parser = argparse.ArgumentParser(
        description="OCR Attendance Sheet Processor"
    )

    parser.add_argument(
        "image",
        help="Attendance sheet image"
    )

    parser.add_argument(
        "--output",
        default="output",
        help="Output directory"
    )

    parser.add_argument(
        "--debug-ocr-crops",
        action="store_true",
        help="Save every roll/name cell crop OCR sees, for visual debugging"
    )

    parser.add_argument(
        "--no-roll-sequence-correction",
        action="store_true",
        help="Disable automatic roll-number sequence correction "
             "(use only if roll numbers are NOT sequential on your sheet)"
    )

    parser.add_argument(
        "--known-names-file",
        default=None,
        help="Optional path to a text file with one known student name "
             "per line, used to fuzzy-correct OCR'd names"
    )

    args = parser.parse_args()

    config = AttendanceConfig()

    if args.debug_ocr_crops:
        config.save_ocr_debug_crops = True

    if args.no_roll_sequence_correction:
        config.enable_roll_sequence_correction = False

    if args.known_names_file:
        with open(args.known_names_file, "r", encoding="utf-8") as f:
            config.known_names = [line.strip() for line in f if line.strip()]
        logger.info("Loaded %d known names for fuzzy matching", len(config.known_names))

    processor = AttendanceProcessor(config=config)

    df = processor.process(
        image_path=args.image,
        output_dir=args.output
    )

    print("\n======================================")
    print("ATTENDANCE PROCESSING COMPLETE")
    print("======================================\n")

    print(df.to_string(index=False))


if __name__ == "__main__":
    main()
