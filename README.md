# School Teacher Attendance App (Android / Kotlin)

Production-ready teacher attendance app with:
- Login (Firebase Authentication)
- Register (with **real email verification**)
- Forgot Password (real **email reset link** — no custom SMTP needed, more secure)
- Dashboard with attendance history
- Mark Attendance (Firestore backed)

Firebase Authentication is used instead of a custom SMTP server because embedding
email/SMTP credentials inside an Android APK is insecure and can be extracted by
anyone. Firebase sends real emails (verification + password reset) from its own
trusted mail servers — this is the standard production approach used by real apps.

## 1. Firebase Project Setup (required — do this first)

1. Go to https://console.firebase.google.com → **Add Project** → name it (e.g. `SchoolAttendance`).
2. Inside the project, click **Add App → Android**.
   - Package name: `com.school.attendance` (must match exactly)
3. Download the generated **`google-services.json`** file.
4. Copy it into: `app/google-services.json` (same folder as `app/build.gradle.kts`).
   - This file is NOT included in this project — you must generate your own from your Firebase console.
5. In Firebase Console → **Build → Authentication → Sign-in method** → Enable **Email/Password**.
6. In Firebase Console → **Build → Firestore Database** → Create database (start in production mode).
7. Under Firestore → **Rules**, paste:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /teachers/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    match /attendance/{docId} {
      allow read: if request.auth != null && resource.data.markedBy == request.auth.uid;
      allow create: if request.auth != null && request.resource.data.markedBy == request.auth.uid;
      allow update, delete: if request.auth != null && resource.data.markedBy == request.auth.uid;
    }
  }
}
```

8. (Optional, recommended) In Authentication → Templates, customize the "Email address verification"
   and "Password reset" email templates with your school's name/logo.

## 2. Open & Run

1. Open this folder in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (it will download dependencies — needs internet).
3. Make sure `app/google-services.json` is in place (step above).
4. Run on an emulator or physical device (minSdk 23 / Android 6.0+).

## 3. App Flow

- First launch → `SplashActivity` checks if a teacher is already logged in.
- Not logged in → `LoginActivity`
  - "Register" → `RegisterActivity` → creates Firebase account → sends real
    verification email → teacher profile saved to Firestore `teachers/{uid}`.
  - "Forgot Password" → `ForgotPasswordActivity` → sends real password reset
    email via `sendPasswordResetEmail()`.
- After login (and only if email is verified) → `DashboardActivity`
  - Shows teacher name/subject and recent attendance list (from Firestore `attendance` collection).
  - "Mark Attendance" → `MarkAttendanceActivity` → saves a record with
    student name, class, date, and status (Present/Absent/Late).
  - "Logout" → signs out and returns to Login.

## 4. Project Structure

```
app/src/main/java/com/school/attendance/
  SplashActivity.kt
  LoginActivity.kt
  RegisterActivity.kt
  ForgotPasswordActivity.kt
  DashboardActivity.kt
  MarkAttendanceActivity.kt
  models/Teacher.kt
  models/AttendanceRecord.kt
  adapters/AttendanceAdapter.kt
app/src/main/res/layout/   -> all XML screens
app/src/main/res/values/   -> strings, colors, themes
```

## 5. Notes for Production Hardening

- Add Firebase App Check to block abuse from non-genuine app installs.
- Add a Cloud Function to auto-delete unverified accounts after 24h.
- Consider adding school-admin role management (Firestore custom claims) if
  multiple schools/admins will use one app.
- Add ProGuard/R8 (`isMinifyEnabled = true`) is already enabled for release builds.
