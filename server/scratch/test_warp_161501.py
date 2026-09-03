import cv2
import numpy as np

img = cv2.imread("output/debug_attendance_20260821_161501.png")
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
blurred = cv2.GaussianBlur(gray, (7, 7), 0)

_, thresh_white = cv2.threshold(blurred, 140, 255, cv2.THRESH_BINARY)
contours, _ = cv2.findContours(thresh_white, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
contours = sorted(contours, key=cv2.contourArea, reverse=True)

c = contours[0]
rect = cv2.minAreaRect(c)
box = np.int32(cv2.boxPoints(rect))
print("MinAreaRect Box Points:")
print(box)

# Draw box on image
annot = img.copy()
cv2.drawContours(annot, [box], 0, (0, 255, 0), 3)
cv2.imwrite("scratch/min_area_rect_box.png", annot)

# Order points
rect_pts = np.zeros((4, 2), dtype="float32")
s = box.sum(axis=1)
rect_pts[0] = box[np.argmin(s)]  # Top-Left
rect_pts[2] = box[np.argmax(s)]  # Bottom-Right
diff = np.diff(box, axis=1)
rect_pts[1] = box[np.argmin(diff)]  # Top-Right
rect_pts[3] = box[np.argmax(diff)]  # Bottom-Left

dst = np.array([
    [0, 0],
    [2000 - 1, 0],
    [2000 - 1, 1400 - 1],
    [0, 1400 - 1]
], dtype="float32")

warped = cv2.warpPerspective(img, cv2.getPerspectiveTransform(rect_pts, dst), (2000, 1400))
cv2.imwrite("scratch/warped_from_161501.png", warped)
print("Saved warped image to scratch/warped_from_161501.png")
