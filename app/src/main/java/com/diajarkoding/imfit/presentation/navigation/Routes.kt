package com.diajarkoding.imfit.presentation.navigation

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val TEMPLATE_LIST = "templates"
    const val CREATE_TEMPLATE = "create_template"
    const val EDIT_TEMPLATE = "edit_template/{templateId}"
    const val EXERCISE_BROWSER = "exercise_browser"
    const val EXERCISE_SELECTION = "exercise_selection/{templateId}"
    const val ACTIVE_WORKOUT = "active_workout/{templateId}"
    const val WORKOUT_SUMMARY = "workout_summary/{workoutLogId}"

    fun editTemplate(templateId: String) = "edit_template/$templateId"
    fun exerciseSelection(templateId: String) = "exercise_selection/$templateId"
    fun activeWorkout(templateId: String) = "active_workout/$templateId"
    fun workoutSummary(workoutLogId: String) = "workout_summary/$workoutLogId"
}
