package com.maks.music.core

import android.content.res.AssetManager
import java.io.File

/**
 * Абстракция чтения JSON-конфигов и ответов:
 * в приложении читает из Android assets, в JUnit-тестах — с диска.
 */
interface AssetProvider {
    fun readText(path: String): String?
    fun listFiles(dir: String): List<String>
}

class AndroidAssetProvider(private val assets: AssetManager) : AssetProvider {
    override fun readText(path: String): String? = runCatching {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText().removePrefix("\uFEFF") }
    }.getOrNull()

    override fun listFiles(dir: String): List<String> = runCatching {
        assets.list(dir)?.toList().orEmpty()
    }.getOrDefault(emptyList())
}

class FileSystemAssetProvider(private val rootDir: File) : AssetProvider {
    override fun readText(path: String): String? = runCatching {
        val f = File(rootDir, path)
        if (!f.exists()) null else f.readText(Charsets.UTF_8).removePrefix("\uFEFF")
    }.getOrNull()

    override fun listFiles(dir: String): List<String> {
        val d = File(rootDir, dir)
        if (!d.isDirectory) return emptyList()
        return d.list()?.toList().orEmpty()
    }
}
