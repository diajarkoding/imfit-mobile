package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.diajarkoding.imfit.R

object Symbols {
    object Filled
    object Outlined
    object AutoMirrored {
        object Filled
    }

    val Default = Filled
}

@get:Composable
val Symbols.Filled.AccessTime: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_schedule_filled)

@get:Composable
val Symbols.Filled.Add: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_add_filled)

@get:Composable
val Symbols.Filled.Cake: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_cake_filled)

@get:Composable
val Symbols.Filled.CalendarMonth: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_calendar_month_filled)

@get:Composable
val Symbols.Filled.CameraAlt: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_photo_camera_filled)

@get:Composable
val Symbols.Filled.Check: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_check_filled)

@get:Composable
val Symbols.Filled.CheckCircle: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_check_circle_filled)

@get:Composable
val Symbols.Filled.Clear: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_close_filled)

@get:Composable
val Symbols.Filled.Close: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_close_filled)

@get:Composable
val Symbols.Filled.DarkMode: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_dark_mode_filled)

@get:Composable
val Symbols.Filled.Delete: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_delete_filled)

@get:Composable
val Symbols.Filled.Edit: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_edit_filled)

@get:Composable
val Symbols.Filled.Email: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_mail_filled)

@get:Composable
val Symbols.Filled.FitnessCenter: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_fitness_center_filled)

@get:Composable
val Symbols.Filled.Home: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_home_filled)

@get:Composable
val Symbols.Filled.Language: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_language_filled)

@get:Composable
val Symbols.Filled.LightMode: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_light_mode_filled)

@get:Composable
val Symbols.Filled.LocalFireDepartment: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_local_fire_department_filled)

@get:Composable
val Symbols.Filled.Pause: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_pause_filled)

@get:Composable
val Symbols.Filled.Person: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_person_filled)

@get:Composable
val Symbols.Filled.PhotoLibrary: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_photo_library_filled)

@get:Composable
val Symbols.Filled.PlayArrow: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_play_arrow_filled)

@get:Composable
val Symbols.Filled.Schedule: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_schedule_filled)

@get:Composable
val Symbols.Filled.Search: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_search_filled)

@get:Composable
val Symbols.Filled.Stop: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_stop_filled)

@get:Composable
val Symbols.Filled.Timer: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_timer_filled)

@get:Composable
val Symbols.Filled.Visibility: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_visibility_filled)

@get:Composable
val Symbols.Filled.VisibilityOff: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_visibility_off_filled)

@get:Composable
val Symbols.Outlined.CalendarMonth: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_calendar_month_outlined)

@get:Composable
val Symbols.Outlined.FitnessCenter: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_fitness_center_outlined)

@get:Composable
val Symbols.Outlined.Home: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_home_outlined)

@get:Composable
val Symbols.AutoMirrored.Filled.ArrowBack: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_arrow_back_filled)

@get:Composable
val Symbols.AutoMirrored.Filled.ExitToApp: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_logout_filled)

@get:Composable
val Symbols.AutoMirrored.Filled.KeyboardArrowLeft: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_keyboard_arrow_left_filled)

@get:Composable
val Symbols.AutoMirrored.Filled.KeyboardArrowRight: ImageVector get() = ImageVector.vectorResource(R.drawable.msr_keyboard_arrow_right_filled)
