package com.school.attendance.models

data class Teacher(
    val uid: String = "",
    val fullName: String = "",
    val employeeId: String = "",
    val city: String = "",
    val district: String = "",
    val state: String = "",
    val email: String = "",
    val schoolName: String = "",
    val schoolCode: String = "",
    val schoolLat: Double = 0.0,
    val schoolLong: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)
