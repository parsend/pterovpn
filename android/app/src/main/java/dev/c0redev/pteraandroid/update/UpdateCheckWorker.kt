package dev.c0redev.pteraandroid.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val um = UpdateManager()
            val c = um.checkLatestRelease()
            if (c != null) {
                UpdatePrefs.setRemoteReleaseTag(applicationContext, c.tag)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
