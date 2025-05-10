package com.example.todolistapp.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.todolistapp.R

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notificationId = inputData.getString("taskId")
        val taskTitle = inputData.getString("taskTitle") ?: "Задача"

        if (notificationId == null) return Result.failure()

        val notification = NotificationCompat.Builder(applicationContext, "reminder_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Напоминание о задаче")
            .setContentText(taskTitle)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        with(NotificationManagerCompat.from(applicationContext)) {
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(notificationId.hashCode(), notification)
            }
        }

        return Result.success()
    }
}