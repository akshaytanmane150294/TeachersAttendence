package com.school.attendance.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.school.attendance.databinding.ItemStudentBinding
import com.school.attendance.models.AttendanceRecord

class AttendanceAdapter(
    private val items: MutableList<AttendanceRecord> = mutableListOf()
) : RecyclerView.Adapter<AttendanceAdapter.ViewHolder>() {

    fun submitList(newItems: List<AttendanceRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemStudentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: AttendanceRecord) {
            binding.tvStudentName.text = record.username.ifBlank { "Unknown" }
            binding.tvClassDate.text = "${record.schoolname.ifBlank { "School" }} • ${record.date}"
            val statusLabel = when (record.status) {
                1 -> "Present"
                0 -> "Absent"
                else -> "Late"
            }
            binding.chipStatus.text = statusLabel
            val color = when (record.status) {
                1 -> Color.parseColor("#2E7D32")
                0 -> Color.parseColor("#D32F2F")
                else -> Color.parseColor("#F9A825")
            }
            binding.chipStatus.chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
            binding.chipStatus.setTextColor(Color.WHITE)
        }
    }
}
