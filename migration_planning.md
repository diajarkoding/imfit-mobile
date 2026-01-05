# Project Brief: IMFIT Mobile App (MVP)

### 1. Project Summary
**App Name:** IMFIT
**Platform:** Mobile (Android/iOS)
**Project Type:** Minimum Viable Product (MVP)
**Core Objective:** To provide a personal gym assistant focused on simple, efficient data logging to help users track their fitness journey and monitor progressive overload, minimizing complex social or gamification features in the initial phase.

### 2. Target Audience
* **Beginners:** Users who need guidance on exercise names and muscle targeting.
* **Intermediate/Advanced:** Users looking to switch from paper notes/spreadsheets to a digital tool that automates volume calculation and history tracking.

---

### 3. Key Feature Analysis (MVP Scope)

Breakdown of technical requirements based on MVP needs:

#### A. Authentication Module
* **Basic Security:** Password encryption (hashing).
* **Validation:** Email format validation, password strength requirements, and password confirmation matching.

#### B. Exercise Database Module
* **Data Structure:** Hierarchy of `Muscle Category` > `Exercise List` > `Exercise Details`.
* **Content:** Static database (hardcoded or fetched from admin backend) containing exercise names, target muscles, and description/tutorial text.

#### C. Workout Planning Module
* **Session CRUD:** Users can Create, Read, Update, and Delete workout session templates (e.g., "Leg Day", "Push Day").
* **Customization:** Ability to add specific exercises to these session templates.

#### D. Workout Execution Module (Active Session)
* **Real-time Logging:** Input fields for `Weight (kg/lbs)`, `Reps`, and `Sets`.
* **Rest Timer:** A countdown timer (automatic or manual trigger) that activates after a user marks a set as "Done".
* **State Management:** The app must persist the "Active Workout" state to prevent data loss if the app is minimized or the screen turns off.

#### E. Session Analytics Module (Post-Workout Logic)
* **Automated Calculations:**
    * **Duration:** `End Time` - `Start Time`.
    * **Total Volume (per exercise):** $\sum (Weight \times Reps \times Sets)$.
    * **Total Volume (per session):** Accumulation of all exercises performed.

---

### 4. User Flow

The following flows are designed for a logical and frictionless user experience (UX).

#### Flow 1: Onboarding (New User)
1.  **Splash Screen:** IMFIT Logo display.
2.  **Registration Screen:** Input Name, Email, Password, Confirm Password -> Tap "Sign Up".
3.  **Login Screen:** Input Email, Password -> Tap "Login".
4.  **Dashboard/Home:** User lands on the main interface.

#### Flow 2: Preparation - Creating a Session Template (Pre-Gym)
*User sets up their routine (e.g., Push Day).*

1.  **Dashboard** -> Tap **"Manage Sessions"** tab.
2.  Tap **"Create New Session"** (+).
3.  Input **Session Name** (e.g., "Chest & Triceps").
4.  Tap **"Add Exercise"**.
5.  **Select Muscle Category** (e.g., "Chest") -> Exercise List appears.
6.  Select **Exercise** (e.g., "Bench Press", "Incline Dumbbell Press").
7.  Repeat steps 5-6 for other muscles (e.g., Triceps).
8.  Tap **"Save Session"**.
9.  The session is now listed on the Dashboard or Sessions menu.

#### Flow 3: Execution - Active Workout (At the Gym)
*User performs the workout based on the created template.*

1.  **Dashboard** -> Select Session (e.g., "Chest & Triceps").
2.  View Summary -> Tap **"Start Workout"**.
3.  **Active Workout Screen:**
    * Display First Exercise (e.g., Bench Press).
    * User performs Set 1 -> Inputs Weight & Reps.
    * User taps checkbox **(Done)** for Set 1.
    * **Rest Timer Popup** triggers (e.g., 90-second countdown).
    * Timer finishes -> Notification (Vibrate/Sound).
    * User proceeds to Set 2, etc.
4.  User swipes/scrolls to Second Exercise -> Repeats logging process.
5.  After all exercises are done -> Tap **"Finish Workout"**.

#### Flow 4: Completion & Reporting (Post-Gym)
1.  **Summary Screen:**
    * Display "Workout Complete!".
    * Display **Total Duration** (e.g., 1h 15m).
    * Display **Total Volume** (e.g., 5,000 kg).
    * Breakdown per exercise (e.g., Bench Press - 3 Sets).
2.  Tap **"Done/Close"** -> Return to Dashboard.

---

### 5. Technical Logic & Calculations

The app requires specific mathematical logic to handle data processing when the "Finish Workout" button is pressed:

1.  **Volume Per Set Formula:**
    $$Volume = Weight \times Reps$$
    *(Example: 50kg x 10 reps = 500kg)*

2.  **Total Workout Volume Formula:**
    $$Total = \sum (Volume_{Set1} + Volume_{Set2} + ... + Volume_{SetN})$$

3.  **Time Tracking:**
    * `StartTime` captured when "Start Workout" is tapped.
    * `EndTime` captured when "Finish Workout" is tapped.
    * `Duration` = `EndTime` - `StartTime`.

---

### 6. Database Schema Overview (Developer Reference)

To ensure feature functionality, the following data entities are required:

* **Users:** `ID`, `Name`, `Email`, `PasswordHash`, `AuthToken`.
* **Exercises:** `ID`, `Name`, `MuscleCategory`, `Description`, `ImageURL`.
* **WorkoutTemplates:** `ID`, `UserID`, `TemplateName` (e.g., "Push Day").
* **TemplateExercises:** `ID`, `TemplateID`, `ExerciseID` (Mapping exercises to templates).
* **WorkoutLogs (History):** `ID`, `UserID`, `Date`, `StartTime`, `EndTime`, `TotalVolume`.
* **WorkoutLogSets:** `ID`, `LogID`, `ExerciseID`, `SetNumber`, `Weight`, `Reps`.

---