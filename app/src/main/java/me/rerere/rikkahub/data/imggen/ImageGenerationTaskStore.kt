package me.rerere.rikkahub.data.imggen

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

interface ImageGenerationTaskStore {
    fun load(): ImageGenerationTask?

    fun save(task: ImageGenerationTask)

    fun clear()
}

class SharedPreferencesImageGenerationTaskStore(
    context: Context,
    private val json: Json,
) : ImageGenerationTaskStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): ImageGenerationTask? {
        val value = preferences.getString(KEY_CURRENT_TASK, null) ?: return null
        return try {
            json.decodeFromString<ImageGenerationTask>(value)
        } catch (error: Exception) {
            Log.e(TAG, "Discarding unreadable image generation task state", error)
            preferences.edit().remove(KEY_CURRENT_TASK).apply()
            null
        }
    }

    override fun save(task: ImageGenerationTask) {
        val saved = preferences.edit()
            .putString(KEY_CURRENT_TASK, json.encodeToString(task))
            .commit()
        if (!saved) {
            throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "Image generation task state could not be persisted",
            )
        }
    }

    override fun clear() {
        if (!preferences.edit().remove(KEY_CURRENT_TASK).commit()) {
            throw ImageGenerationException(
                ImageGenerationFailureKind.CONFIGURATION,
                "Image generation task state could not be cleared",
            )
        }
    }

    private companion object {
        const val TAG = "ImageGenerationTaskStore"
        const val PREFERENCES_NAME = "image_generation_tasks"
        const val KEY_CURRENT_TASK = "current_task"
    }
}
