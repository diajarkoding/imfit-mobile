package com.diajarkoding.imfit.data.local

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
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
            .map { TemplateExercise(exercise = it) }
        val tricepsExercises = FakeExerciseDataSource.getExercisesByCategory(MuscleCategory.TRICEPS).take(2)
            .map { TemplateExercise(exercise = it) }

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
            .map { TemplateExercise(exercise = it) }
        val bicepsExercises = FakeExerciseDataSource.getExercisesByCategory(MuscleCategory.BICEPS).take(2)
            .map { TemplateExercise(exercise = it) }

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
            .map { TemplateExercise(exercise = it) }

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
        val dayInMs = 86400000L

        val pushTemplate = templates.find { it.name == "Push Day" } ?: templates.first()
        val pushLogs = pushTemplate.exercises.map { templateExercise ->
            ExerciseLog(
                exercise = templateExercise.exercise,
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
                templateName = pushTemplate.name,
                date = System.currentTimeMillis() - dayInMs * 2,
                startTime = System.currentTimeMillis() - dayInMs * 2,
                endTime = System.currentTimeMillis() - dayInMs * 2 + 3600000,
                totalVolume = pushLogs.sumOf { it.totalVolume.toDouble() }.toFloat(),
                exerciseLogs = pushLogs
            )
        )

        val pullTemplate = templates.find { it.name == "Pull Day" } ?: templates[1]
        val pullLogs = pullTemplate.exercises.map { templateExercise ->
            ExerciseLog(
                exercise = templateExercise.exercise,
                sets = listOf(
                    WorkoutSet(1, 50f, 12, true),
                    WorkoutSet(2, 55f, 10, true),
                    WorkoutSet(3, 60f, 8, true)
                )
            )
        }
        workoutLogs.add(
            WorkoutLog(
                id = "log_2",
                userId = "user_1",
                templateName = pullTemplate.name,
                date = System.currentTimeMillis() - dayInMs * 4,
                startTime = System.currentTimeMillis() - dayInMs * 4,
                endTime = System.currentTimeMillis() - dayInMs * 4 + 4200000,
                totalVolume = pullLogs.sumOf { it.totalVolume.toDouble() }.toFloat(),
                exerciseLogs = pullLogs
            )
        )

        val legTemplate = templates.find { it.name == "Leg Day" } ?: templates[2]
        val legLogs = legTemplate.exercises.map { templateExercise ->
            ExerciseLog(
                exercise = templateExercise.exercise,
                sets = listOf(
                    WorkoutSet(1, 80f, 10, true),
                    WorkoutSet(2, 90f, 8, true),
                    WorkoutSet(3, 100f, 6, true)
                )
            )
        }
        workoutLogs.add(
            WorkoutLog(
                id = "log_3",
                userId = "user_1",
                templateName = legTemplate.name,
                date = System.currentTimeMillis() - dayInMs * 6,
                startTime = System.currentTimeMillis() - dayInMs * 6,
                endTime = System.currentTimeMillis() - dayInMs * 6 + 3900000,
                totalVolume = legLogs.sumOf { it.totalVolume.toDouble() }.toFloat(),
                exerciseLogs = legLogs
            )
        )

        workoutLogs.add(
            WorkoutLog(
                id = "log_4",
                userId = "user_1",
                templateName = pushTemplate.name,
                date = System.currentTimeMillis() - dayInMs * 9,
                startTime = System.currentTimeMillis() - dayInMs * 9,
                endTime = System.currentTimeMillis() - dayInMs * 9 + 3300000,
                totalVolume = pushLogs.sumOf { it.totalVolume.toDouble() }.toFloat(),
                exerciseLogs = pushLogs
            )
        )

        workoutLogs.add(
            WorkoutLog(
                id = "log_5",
                userId = "user_1",
                templateName = pullTemplate.name,
                date = System.currentTimeMillis() - dayInMs * 11,
                startTime = System.currentTimeMillis() - dayInMs * 11,
                endTime = System.currentTimeMillis() - dayInMs * 11 + 4000000,
                totalVolume = pullLogs.sumOf { it.totalVolume.toDouble() }.toFloat(),
                exerciseLogs = pullLogs
            )
        )

        workoutLogs.add(
            WorkoutLog(
                id = "log_6",
                userId = "user_1",
                templateName = legTemplate.name,
                date = System.currentTimeMillis() - dayInMs * 13,
                startTime = System.currentTimeMillis() - dayInMs * 13,
                endTime = System.currentTimeMillis() - dayInMs * 13 + 3600000,
                totalVolume = legLogs.sumOf { it.totalVolume.toDouble() }.toFloat(),
                exerciseLogs = legLogs
            )
        )
    }

    fun getTemplates(userId: String): List<WorkoutTemplate> {
        return templates.filter { it.userId == userId }
    }

    fun getTemplateById(templateId: String): WorkoutTemplate? {
        return templates.find { it.id == templateId }
    }

    fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        val template = WorkoutTemplate(
            id = "template_${System.currentTimeMillis()}",
            userId = userId,
            name = name,
            exercises = exercises
        )
        templates.add(template)
        return template
    }

    fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val index = templates.indexOfFirst { it.id == templateId }
        if (index == -1) return null

        val updated = templates[index].copy(name = name, exercises = exercises)
        templates[index] = updated
        return updated
    }

    fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? {
        val index = templates.indexOfFirst { it.id == templateId }
        if (index == -1) return null

        val template = templates[index]
        val updatedExercises = template.exercises.map { templateExercise ->
            if (templateExercise.id == exerciseId) {
                templateExercise.copy(sets = sets, reps = reps, restSeconds = restSeconds)
            } else {
                templateExercise
            }
        }
        val updated = template.copy(exercises = updatedExercises)
        templates[index] = updated
        return updated
    }

    fun deleteTemplate(templateId: String): Boolean {
        return templates.removeIf { it.id == templateId }
    }

    fun startWorkout(template: WorkoutTemplate): WorkoutSession {
        val exerciseLogs = template.exercises.map { templateExercise ->
            ExerciseLog(
                exercise = templateExercise.exercise,
                sets = (1..templateExercise.sets).map { setNum ->
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
