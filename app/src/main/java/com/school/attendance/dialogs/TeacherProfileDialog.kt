package com.school.attendance.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import com.school.attendance.LoginActivity
import com.school.attendance.databinding.DialogTeacherProfileBinding
import com.school.attendance.network.AuthManager

object TeacherProfileDialog {

    fun show(activity: Activity) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val binding = DialogTeacherProfileBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        // Make background transparent so rounded card corners show nicely
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val fullName = AuthManager.getTeacherName()
        val teacherCode = AuthManager.getTeacherCode()
        val schoolName = AuthManager.getSchoolName()
        val schoolCode = AuthManager.getSchoolCode()
        val udiseId = AuthManager.getUdiseId().ifEmpty { schoolCode }
        val designation = AuthManager.getDesignation()
        val mobileNo = AuthManager.getMobileNo()

        // Avatar Initial
        val initial = fullName.trim().firstOrNull()?.uppercase() ?: "T"
        binding.tvAvatarInitial.text = initial

        // Profile Details
        binding.tvProfileFullName.text = fullName
        binding.tvProfileDesignation.text = designation.ifEmpty { "Teacher" }
        binding.tvProfileTeacherCode.text = teacherCode.ifEmpty { "N/A" }
        binding.tvProfileSchoolName.text = schoolName.ifEmpty { "Government School" }
        binding.tvProfileUdiseCode.text = udiseId.ifEmpty { "N/A" }
        binding.tvProfileMobileNo.text = if (mobileNo.isNotEmpty()) mobileNo else "N/A"

        // Dismiss
        binding.btnDismissDialog.setOnClickListener {
            dialog.dismiss()
        }

        // Logout
        binding.btnProfileLogout.setOnClickListener {
            dialog.dismiss()
            AuthManager.clearToken()
            val intent = Intent(activity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            activity.startActivity(intent)
            activity.finish()
        }

        dialog.show()
    }
}
