package com.diajarkoding.imfit.data.local

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.WorkoutTemplate

object FakeWorkoutDataSource {

    private val templates = mutableListOf<WorkoutTemplate>()
    private val workoutLogs = mutableListOf<WorkoutLog>()
    private var activeSession: WorkoutSession? = null

    init {
        initializeSampleTemplates()
        initializeSampleWorkoutLogs()
    }

    private fun initializeSampleTemplates() {
        val chestExercises = FakeExerciseDataSource.getExercisesByCategory(MuscleCategory.CHEST).take(3)
        val tricepsExercises = FakeExerciseDataSource.getExercisesByCategory(MuscleCategory.TRICEPS).take(2)

        templates.add(
            WorkoutTemplate(
                id = "template_1",
                userId = "user_1",
                name = "Push Day",
                exercises = chestExercises + tricepsExercises,
                createdAt = System.currentTimeMillis() - 86400000 * 7
            )
        )

        val backExercises = FakeExerciseDataSource.getExercisesByCategory(MuscleCategory.BACK).take(3)
        val bicepsExercises = FakeExerciseDataSource.getExercisesByCategory(MuscleCategory.BICEPS).take(2)

        templates.add(
            WorkoutTemplate(
                id = "template_2",
                userId = "user_1",
                name = "Pull Day",
                exercises = backExercises + bicepsExercises,
                createdAt = System.currentTimeMillis() - 86400000 * 5
            )
        )

        val legExercises = FakeExerciseDataSource.getExercisesByCategory(MuscleCategory.LEGS).take(5)

        templates.add(
            WorkoutTemplate(
                id = "template_3",
                userId = "user_1",
                name = "Leg Day",
                exercises = legExercises,
                createdAt = System.currentTimeMillis() - 86400000 * 3
            )
        )
    }

    private fun initializeSampleWorkoutLogs() {
        val template = templates.first()
        val exerciseLogs = template.exercises.map { exercise ->
            ExerciseLog(
                exercise = exercise,
                sets = listOf(
                    WorkoutSet(1, 60f, 10, true),
                    WorkoutSet(2, 65f, 8, true),
                    WorkoutSet(3, 70f, 6, true)
                )
            )
        }

        workoutLogs.add(
            WorkoutLog(
                id = "log_1",
                userId = "user_1",
                templateName = template.name,
                date = System.currentTimeMillis() - 86400000 * 2,
                startTime = System.currentTimeMillis() - 86400000 * 2,
                endTime = System.currentTimeMillis() - 86400000 * 2 + 3600000,
                totalVolume = exerciseLogs.sumOf { it.totalVolume.toDouble() }.toFloat(),
                exerciseLogs = exerciseLogs
            )
        )
    }

    // Template Operations
    fun getTemplates(userId: String): List<WorkoutTemplate> {
        return templates.filter { it.userId == userId }
    }

    fun getTemplateById(templateId: String): WorkoutTemplate? {
        return templates.find { it.id == templateId }
    }

    fun createTemplate(userId: String, name: String, exercises: List<Exercise>): WorkoutTemplate {
        val template = WorkoutTemplate(
            id = "template_${System.currentTimeMillis()}",
            userId = userId,
            name = name,
            exercises = exercises
        )
        templates.add(template)
        return template
    }

    fun updateTemplate(templateId: String, name: String, exercises: List<Exercise>): WorkoutTemplate? {
        val index = templates.indexOfFirst { it.id == templateId }
        if (index == -1) return null

        val updated = templates[index].copy(name = name, exercises = exercises)
        templates[index] = updated
        return updated
    }

    fun deleteTemplate(templateId: String): Boolean {
        return templates.removeIf { it.id == templateId }
    }

    // Active Session Operations
    fun startWorkout(template: WorkoutTemplate, defaultSetsPerExercise: Int = 3): WorkoutSession {
        val exerciseLogs = template.exercises.map { exercise ->
            ExerciseLog(
                exercise = exercise,
                sets = (1..defaultSetsPerExercise).map { setNum ->
                    WorkoutSet(setNumber = setNum)
                }
            )
        }

        val session = WorkoutSession(
            id = "session_${System.currentTimeMillis()}",
            templateId = template.id,
            templateName = template.name,
            startTime = System.currentTimeMillis(),
            exerciseLogs = exerciseLogs
        )

        activeSession = session
        return session
    }

    fun getActiveSession(): WorkoutSession? = activeSession

    fun updateActiveSession(session: WorkoutSession) {
        activeSession = session
    }

    fun finishWorkout(): WorkoutLog? {
        val session = activeSession ?: return null

        val workoutLog = WorkoutLog(
            id = "log_${System.currentTimeMillis()}",
            userId = FakeUserDataSource.getCurrentUser()?.id ?: "unknown",
            templateName = session.templateName,
            date = session.startTime,
            startTime = session.startTime,
            endTime = System.currentTimeMillis(),
            totalVolume = session.totalVolume,
            exerciseLogs = session.exerciseLogs
        )

        workoutLogs.add(0, workoutLog)
        activeSession = null
        return workoutLog
    }

    fun cancelWorkout() {
        activeSession = null
    }

    // Workout Log Operations
    fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        return workoutLogs.filter { it.userId == userId }.sortedByDescending { it.date }
    }

    fun getWorkoutLogById(logId: String): WorkoutLog? {
        return workoutLogs.find { it.id == logId }
    }

    fun getLastWorkoutLog(userId: String): WorkoutLog? {
        return workoutLogs.filter { it.userId == userId }.maxByOrNull { it.date }
    }
}
