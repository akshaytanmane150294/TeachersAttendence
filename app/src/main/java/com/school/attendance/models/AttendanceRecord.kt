package com.school.attendance.models

data class AttendanceRecord(
    var id: String = "",
    val username: String = "",
    val schoolname: String = "",
    val schoolcode: String = "",
    val date: String = "",
    val status: Int = 1, // 1 for Present, 0 for Absent
    val timestamp: Long = System.currentTimeMillis(),
    val userId: String = "",
    val markedBy: String = ""
)
