package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity

class FilesRepository(
    private val dao: ManagedFileDAO,
) {
    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity = dao.insertOrGet(file)

    suspend fun update(file: ManagedFileEntity) {
        require(dao.update(file) == 1) {
            "Managed file update rejected because its row or stable identity changed: ${file.id}"
        }
    }

    suspend fun getById(id: Long): ManagedFileEntity? = dao.getById(id)

    suspend fun getByFileId(fileId: String): ManagedFileEntity? = dao.getByFileId(fileId)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = dao.listByFolder(folder)

    suspend fun deleteById(id: Long): Int = dao.deleteById(id)

    suspend fun deleteByPath(relativePath: String): Int = dao.deleteByPath(relativePath)

    suspend fun deleteByFolder(folder: String): Int = dao.deleteByFolder(folder)
}
