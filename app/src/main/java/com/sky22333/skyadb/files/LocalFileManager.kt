package com.sky22333.skyadb.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class LocalFileManager(
    private val context: Context,
) {
    fun displayName(uri: Uri): String {
        val fromCursor = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }

        return fromCursor
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "selected-${System.currentTimeMillis()}"
    }

    fun copyToCache(uri: Uri, preferredName: String = displayName(uri)): File {
        val safeName = preferredName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val target = File(context.cacheDir, "picked/$safeName")
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取选择的文件" }
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    fun createExportApkFile(packageName: String): File {
        val safeName = packageName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "app" }
        val targetDir = File(context.cacheDir, "exported-apps")
        targetDir.mkdirs()
        cleanupApkFiles(targetDir)
        return File(targetDir, "$safeName.apk")
    }

    fun copyToUri(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri).use { output ->
            requireNotNull(output) { "无法写入选择的保存位置" }
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun cleanupApkFiles(targetDir: File) {
        targetDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.forEach { file -> runCatching { file.delete() } }
    }
}
