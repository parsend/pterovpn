package dev.c0redev.pteraandroid

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.c0redev.pteraandroid.update.UpdateCheckWorker
import java.util.concurrent.TimeUnit

class PteraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniquePeriodicWork(
            "github_release_check",
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
        wm.enqueue(
            OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(constraints)
                .build(),
        )
    }
}
