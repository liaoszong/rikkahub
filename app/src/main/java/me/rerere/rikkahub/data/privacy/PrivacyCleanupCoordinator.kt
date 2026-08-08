package me.rerere.rikkahub.data.privacy

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.quality.QualityMetricsRecorder

data class PrivacyCleanupReport(val memoryScopesCleared: Int, val rawPayloadFilesDeleted: Int)

class PrivacyCleanupCoordinator(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun clearAgentData(settings: Settings): PrivacyCleanupReport = withContext(Dispatchers.IO) {
        val scopes = buildSet {
            add(MemoryRepository.GLOBAL_MEMORY_ID)
            settings.assistants.forEach { add(it.id.toString()) }
        }
        scopes.forEach { memoryRepository.deleteMemoriesOfAssistant(it) }
        val directory = File(context.filesDir, FileFolders.CONTENT_BLOBS)
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        val deleted = files.count { it.delete() }
        if (directory.listFiles().isNullOrEmpty()) directory.delete()
        QualityMetricsRecorder(context).clear()
        PrivacyCleanupReport(scopes.size, deleted)
    }
}
