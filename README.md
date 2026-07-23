# School Teacher & Student Attendance App (Android / Kotlin)

A production-ready Android application for schools to manage both Teacher and Student attendance. It features Firebase authentication, Firestore database, and **Gemini 1.5 Pro AI** integration for scanning physical attendance sheets.

## 🚀 Key Features

- **Teacher Management**: Secure registration with real email verification and password reset via Firebase.
- **Teacher Attendance**: Mark attendance with real-time location (GPS) and photo capture.
- **AI Student Scanner**: Scan physical attendance sheets using Gemini 1.5 Pro. Automatically extracts student names, roll numbers, and daily attendance status with high precision.
- **Dynamic Dashboard**: View attendance history and manage profiles.
- **Real-time Sync**: All data is synced instantly with Firebase Firestore.

## 🛠 Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM / Jetpack (ViewBinding, Lifecycle, Coroutines)
- **Database**: Firebase Firestore
- **Authentication**: Firebase Auth
- **AI/ML**: Google Gemini 1.5 Pro (Generative AI SDK)
- **Networking**: Ktor Client
- **UI**: Material Components (Day/Night support)

## 📋 Prerequisites & Setup

### 1. Firebase Setup
1. Create a project at [Firebase Console](https://console.firebase.google.com/).
2. Add an Android App with package name `com.school.attendance`.
3. Download `google-services.json` and place it in the `app/` directory.
4. Enable **Email/Password** in Authentication.
5. Create a **Firestore Database** and **Firebase Storage** bucket.

### 2. Google AI Setup
1. Get an API Key from [Google AI Studio](https://aistudio.google.com/).
2. Open `StudentAttendanceScanActivity.kt` and paste your key in the `GEMINI_API_KEY` field.

### 3. Build the Project
1. Open the project in **Android Studio** (Hedgehog or newer).
2. Click **Sync Project with Gradle Files**.
3. Run the app on a physical device (recommended for Camera/GPS testing).

## 📂 Project Structure

- `com.school.attendance/`
  - `activities/`: Splash, Login, Register, Dashboard, etc.
  - `models/`: Data classes for Teacher and Attendance.
  - `adapters/`: RecyclerView adapters for history lists.
- `StudentAttendanceScanActivity.kt`: The core AI engine for processing attendance sheets.
- `MarkTeacherAttendanceActivity.kt`: Handles GPS location and teacher selfie capture.

## 🛡 Security Notes
- **App Check**: Temporarily disabled in `SchoolAttendanceApplication.kt` for development. Enable it before release for enhanced security.
- **ProGuard**: Minification is enabled for release builds to protect the source code.

---
Developed with ❤️ for Schools.
