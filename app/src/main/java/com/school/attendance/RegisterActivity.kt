package com.school.attendance

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.school.attendance.databinding.ActivityRegisterBinding
import com.school.attendance.models.Teacher

class RegisterActivity : AppCompatActivity() {
    private val NAME_REGEX = Regex("^[A-Za-z ]{3,50}$")
    private val EMPLOYEE_REGEX = Regex("^\\d{4,10}$")
    private val LOCATION_REGEX = Regex("^[A-Za-z ]{2,40}$")
    private val PASSWORD_REGEX = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#\$%^&+=!]).{8,}$")

    private lateinit var binding: ActivityRegisterBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    data class School(val name: String, val code: String, val lat: Double, val lng: Double) {
        override fun toString(): String = "$name ($code)"
    }

    private var bhilaiSchools = mutableListOf<School>()
    private var selectedSchool: School? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fetchSchoolsFromFirestore()

        binding.btnRegister.setOnClickListener { attemptRegister() }
        binding.tvGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun fetchSchoolsFromFirestore() {
        binding.progressBar.visibility = View.VISIBLE
        val collectionsToTry = listOf("schools", "Schools", "school", "School", "school_list", "SchoolList", "AttendencTable")
        val uniqueSchools = mutableSetOf<School>()
        
        var loadedCount = 0
        var foundInAny = false
        
        for (colName in collectionsToTry) {
            firestore.collection(colName)
                .get()
                .addOnSuccessListener { documents ->
                    if (!documents.isEmpty) {
                        foundInAny = true
                    }
                    for (document in documents) {
                        // Check if the document itself is a school
                        // Using .get().toString() to be more flexible than getString()
                        val name = (document.get("name") ?: 
                                   document.get("schoolName") ?: 
                                   document.get("school_name") ?: 
                                   document.get("school name") ?:
                                   document.get("Name"))?.toString()
                                   
                        if (name != null && name.isNotEmpty() && name != "School Name" && name != "null") {
                            val code = (document.get("code") ?: 
                                       document.get("schoolCode") ?: 
                                       document.get("school_code") ?: 
                                       document.get("schoolcode") ?: "")?.toString() ?: ""
                            
                            val lat = (document.get("lat") as? Number)?.toDouble() ?: 
                                      (document.get("latitude") as? Number)?.toDouble() ?: 0.0
                            val lng = (document.get("long") as? Number)?.toDouble() ?: 
                                      (document.get("longitude") as? Number)?.toDouble() ?: 
                                      (document.get("lng") as? Number)?.toDouble() ?: 0.0
                            
                            uniqueSchools.add(School(name, code, lat, lng))
                        }

                        // Check if the document contains a list/array of schools
                        val data = document.data
                        for ((key, value) in data) {
                            if (value is List<*>) {
                                val list = value as List<*>
                                list.forEach { item ->
                                    if (item is Map<*, *>) {
                                        val m = item as Map<String, Any>
                                        val n = (m["name"] ?: m["schoolName"] ?: m["school_name"] ?: m["school name"])?.toString() ?: ""
                                        val c = (m["code"] ?: m["schoolCode"] ?: m["school_code"] ?: m["schoolcode"])?.toString() ?: ""
                                        val la = (m["lat"] as? Number)?.toDouble() ?: (m["latitude"] as? Number)?.toDouble() ?: 0.0
                                        val lo = (m["long"] as? Number)?.toDouble() ?: (m["longitude"] as? Number)?.toDouble() ?: 0.0
                                        if (n.isNotEmpty() && n != "null") {
                                            uniqueSchools.add(School(n, c, la, lo))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    loadedCount++
                    if (loadedCount == collectionsToTry.size) {
                        finalizeSchoolList(uniqueSchools, foundInAny)
                    }
                }
                .addOnFailureListener { e ->
                    // Log the error to help debug
                    android.util.Log.e("RegisterActivity", "Error fetching collection $colName", e)
                    loadedCount++
                    if (loadedCount == collectionsToTry.size) {
                        finalizeSchoolList(uniqueSchools, foundInAny)
                    }
                }
        }
    }

    private fun finalizeSchoolList(uniqueSchools: Set<School>, foundInAny: Boolean) {
        binding.progressBar.visibility = View.GONE
        bhilaiSchools.clear()
        bhilaiSchools.addAll(uniqueSchools)
        bhilaiSchools.sortBy { it.name.lowercase() }
        
        if (bhilaiSchools.isEmpty()) {
            val message = if (!foundInAny) {
                "No documents found in schools collections. Please ensure your collection is named 'schools' and contains data."
            } else {
                "Documents found but could not parse school data. Please check field names (name, schoolName, etc.)"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Successfully loaded ${bhilaiSchools.size} schools", Toast.LENGTH_SHORT).show()
        }
        setupSchoolDropdown()
    }

    private fun setupSchoolDropdown() {
        // Use a simple layout for the dropdown items
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bhilaiSchools)
        binding.actvSchool.setAdapter(adapter)
        
        // Ensure all items are shown when clicking the dropdown
        binding.actvSchool.threshold = 0
        
        binding.actvSchool.setOnClickListener {
            binding.actvSchool.showDropDown()
        }
        
        binding.actvSchool.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.actvSchool.showDropDown()
            }
        }

        binding.actvSchool.setOnItemClickListener { _, _, position, _ ->
            // Use the adapter to get the item correctly (essential for AutoCompleteTextView)
            val selectedItem = adapter.getItem(position)
            selectedSchool = selectedItem
            binding.actvSchool.error = null
            // Set the text to the selected school name
            binding.actvSchool.setText(selectedItem?.name, false)
        }
    }

    private fun attemptRegister() {
        val fullName = binding.etFullName.text.toString().trim()
        val employeeId = binding.etEmployeeId.text.toString().trim()
        val city = binding.etCity.text.toString().trim()
        val district = binding.etDistrict.text.toString().trim()
        val state = binding.etState.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (!NAME_REGEX.matches(fullName)) {
            binding.etFullName.error = "Enter valid full name"
            return
        }
        if (!EMPLOYEE_REGEX.matches(employeeId)) {
            binding.etEmployeeId.error = "Employee ID must be 4-10 digits"
            return
        }
        if (!LOCATION_REGEX.matches(city)) {
            binding.etCity.error = "Enter valid city"
            return
        }
        if (!LOCATION_REGEX.matches(state)) {
            binding.etState.error = "Enter valid state"
            return
        }
        if (selectedSchool == null) {
            binding.actvSchool.error = "Please select a school"
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Enter a valid email"
            return
        }
        if (!PASSWORD_REGEX.matches(password)) {
            binding.etPassword.error = "Password must contain Uppercase, Lowercase, Number & Special Character"
            return
        }
        if (password != confirmPassword) {
            binding.etConfirmPassword.error = "Passwords do not match"
            return
        }

        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener
                    val teacher = Teacher(
                        uid = uid,
                        fullName = fullName,
                        employeeId = employeeId,
                        city = city,
                        district = district,
                        state = state,
                        email = email,
                        schoolName = selectedSchool?.name ?: "",
                        schoolCode = selectedSchool?.code ?: "",
                        schoolLat = selectedSchool?.lat ?: 0.0,
                        schoolLong = selectedSchool?.lng ?: 0.0
                    )

                    // Write to both capitalized and lowercase to avoid Permission Denied issues
                    firestore.collection("Teachers").document(uid).set(teacher)
                    firestore.collection("teachers").document(uid).set(teacher)
                        .addOnSuccessListener {
                            auth.currentUser?.sendEmailVerification()
                                ?.addOnCompleteListener { task ->
                                    setLoading(false)
                                    if (task.isSuccessful) {
                                        Toast.makeText(this, "Verification email sent. Please check your inbox.", Toast.LENGTH_LONG).show()
                                        auth.signOut()
                                        startActivity(Intent(this, LoginActivity::class.java))
                                        finish()
                                    } else {
                                        Toast.makeText(this, "Email error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                        }
                        .addOnFailureListener { e ->
                            setLoading(false)
                            Toast.makeText(this, "Save failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                } else {
                    setLoading(false)
                    Toast.makeText(this, task.exception?.localizedMessage ?: "Registration failed", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
    }
}
