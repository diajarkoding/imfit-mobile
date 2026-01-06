 package com.diajarkoding.imfit.di
 
 import android.content.Context
 import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
 import dagger.Module
 import dagger.Provides
 import dagger.hilt.InstallIn
 import dagger.hilt.android.qualifiers.ApplicationContext
 import dagger.hilt.components.SingletonComponent
 import javax.inject.Singleton
 
 @Module
 @InstallIn(SingletonComponent::class)
 object NotificationModule {
     
     @Provides
     @Singleton
     fun provideWorkoutNotificationManager(
         @ApplicationContext context: Context
     ): WorkoutNotificationManager {
         return WorkoutNotificationManager(context)
     }
 }
