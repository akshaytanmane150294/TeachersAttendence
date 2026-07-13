package com.school.attendance.models

data class AttendanceRecord(
    var id: String = "",
    val studentName: String = "",
    val className: String = "",
    val date: String = "",
    val status: String = "Present", // Present, Absent, Late
    val markedBy: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
