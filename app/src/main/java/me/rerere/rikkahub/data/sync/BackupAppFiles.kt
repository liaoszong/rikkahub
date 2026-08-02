package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.nio.file.Files

internal data class BackupAppFile(
    val source: File,
    val archivePath: String,
)

internal val BACKUP_APP_FILE_ROOTS = listOf(
    FileFolders.UPLOAD,
    FileFolders.SKILLS,
    FileFolders.FONTS,
    FileFolders.LEGACY_GENERATED_IMAGES,
    FileFolders.CHAT_GENERATED_IMAGES,
)

/**
 * Produces the single deterministic app-file archive plan shared by every backup backend.
 *
 * Symbolic links are deliberately excluded: backup files must originate below the approved
 * app-private roots and must restore to the same relative path.
 */
internal fun collectBackupAppFiles(filesDir: File): List<BackupAppFile> {
    val canonicalFilesDir = filesDir.canonicalFile
    return buildList {
        BACKUP_APP_FILE_ROOTS.forEach { rootName ->
            val root = File(canonicalFilesDir, rootName)
            if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return@forEach

            val canonicalRoot = root.canonicalFile
            check(canonicalRoot.isStrictlyWithin(canonicalFilesDir)) {
                "Backup root escapes the app files directory: $rootName"
            }
            collectBackupFilesRecursively(
                rootName = rootName,
                root = canonicalRoot,
                directory = canonicalRoot,
                destination = this,
            )
        }
    }
}

internal fun isRestorableAppFile(name: String): Boolean {
    if (
        name.isBlank() ||
        '\\' in name ||
        '\u0000' in name ||
        ':' in name ||
        name.startsWith('/')
    ) {
        return false
    }

    val segments = name.split('/')
    return segments.size >= 2 &&
        segments.first() in BACKUP_APP_FILE_ROOTS &&
        segments.drop(1).all { it.isNotBlank() && it != "." && it != ".." }
}

internal fun resolveRestorableAppFile(filesDir: File, archivePath: String): File {
    require(isRestorableAppFile(archivePath)) {
        "Backup entry is outside the approved app file roots: $archivePath"
    }
    return SafeBackupArchive.resolveWithin(filesDir, archivePath)
}

private fun collectBackupFilesRecursively(
    rootName: String,
    root: File,
    directory: File,
    destination: MutableList<BackupAppFile>,
) {
    directory.listFiles()
        ?.sortedBy { it.name }
        ?.forEach { candidate ->
            if (Files.isSymbolicLink(candidate.toPath())) return@forEach

            when {
                candidate.isDirectory -> collectBackupFilesRecursively(
                    rootName = rootName,
                    root = root,
                    directory = candidate,
                    destination = destination,
                )

                candidate.isFile -> {
                    val source = candidate.canonicalFile
                    check(source.isStrictlyWithin(root)) {
                        "Backup file escapes its approved root: ${candidate.path}"
                    }
                    val relativePath = source.relativeTo(root).invariantSeparatorsPath
                    val archivePath = "$rootName/$relativePath"
                    check(isRestorableAppFile(archivePath)) {
                        "Backup file cannot be represented by a safe archive path: ${candidate.path}"
                    }
                    destination += BackupAppFile(source, archivePath)
                }
            }
        }
}

private fun File.isStrictlyWithin(root: File): Boolean =
    path.startsWith(root.path + File.separator)
