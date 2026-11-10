package com.movtery.zalithlauncher.feature.verify

import com.google.gson.Gson
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.path.PathManager
import java.io.File

/**
 * Bench-verified fast file verification (stat manifest).
 *
 * Instead of re-hashing every asset/library on each launch, remember
 * (size + modification time + sha1) after each successful full verification.
 * When the file stat is unchanged, the expensive hash pass is skipped.
 *
 * Every [FULL_VERIFY_INTERVAL] passes a full re-hash is forced to catch the
 * rare case where content changed while size/mtime stayed identical.
 *
 * Controlled by the "fastFileVerify" setting; when disabled, every call
 * returns false/no-ops and the launcher behaves exactly as before.
 */
object FileVerifyCache {
    private const val FULL_VERIFY_INTERVAL = 10
    private const val CACHE_FILE_NAME = "file_verify_cache.json"

    data class Entry(val size: Long = 0, val mtime: Long = 0, val sha1: String? = null)

    private data class Manifest(var pass: Int = 0, var files: MutableMap<String, Entry>? = null)

    private val gson = Gson()
    private val lock = Any()
    private var manifest = Manifest()
    @Volatile private var loaded = false
    @Volatile private var forceFullPass = false
    private var dirty = false

    private fun cacheFile() = File(PathManager.DIR_DATA, CACHE_FILE_NAME)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            manifest = runCatching {
                val file = cacheFile()
                if (file.exists() && file.length() > 0) {
                    gson.fromJson(file.readText(), Manifest::class.java) ?: Manifest()
                } else Manifest()
            }.getOrElse { e ->
                Logging.w("FileVerifyCache", "Failed to load verify cache", e)
                Manifest()
            }
            loaded = true
        }
    }

    /**
     * Chama uma vez por download/verificação (antes de agendar as tarefas).
     * Incrementa o contador de passes e decide se este pass força o hash completo.
     */
    @JvmStatic
    fun beginPass() {
        if (!AllSettings.fastFileVerify.getValue()) return
        ensureLoaded()
        synchronized(lock) {
            manifest.pass++
            forceFullPass = manifest.pass % FULL_VERIFY_INTERVAL == 0
            dirty = true
        }
    }

    /** Persiste o manifest ao final do pass. */
    @JvmStatic
    fun endPass() {
        if (!AllSettings.fastFileVerify.getValue()) return
        synchronized(lock) {
            if (!dirty) return
            runCatching {
                val file = cacheFile()
                file.parentFile?.mkdirs()
                file.writeText(gson.toJson(manifest))
                dirty = false
            }.onFailure { e ->
                Logging.w("FileVerifyCache", "Failed to save verify cache", e)
            }
        }
    }

    /**
     * @return true quando o arquivo pode ser pulado sem hashear
     * (stat inalterado desde a última verificação completa).
     */
    @JvmStatic
    fun shouldSkip(file: File, expectedSha1: String?): Boolean {
        if (!AllSettings.fastFileVerify.getValue()) return false
        if (expectedSha1.isNullOrEmpty()) return false
        if (forceFullPass) return false
        ensureLoaded()
        val entry = synchronized(lock) {
            manifest.files?.get(file.absolutePath)
        } ?: return false
        return entry.size == file.length() &&
                entry.mtime == file.lastModified() &&
                entry.sha1.equals(expectedSha1, true)
    }

    /**
     * @return true quando o stat do arquivo bate com uma entrada já verificada.
     * Usado para pular também o fetch remoto de .sha1 de bibliotecas maven.
     */
    @JvmStatic
    fun isFresh(file: File): Boolean {
        if (!AllSettings.fastFileVerify.getValue()) return false
        if (forceFullPass) return false
        ensureLoaded()
        val entry = synchronized(lock) {
            manifest.files?.get(file.absolutePath)
        } ?: return false
        return entry.size == file.length() && entry.mtime == file.lastModified()
    }

    /** Registra uma verificação bem-sucedida (hash completo ou download validado). */
    @JvmStatic
    fun record(file: File, sha1: String?) {
        if (!AllSettings.fastFileVerify.getValue()) return
        if (sha1.isNullOrEmpty()) return
        if (!file.exists()) return
        ensureLoaded()
        synchronized(lock) {
            val files = manifest.files ?: HashMap<String, Entry>().also { manifest.files = it }
            files[file.absolutePath] = Entry(file.length(), file.lastModified(), sha1)
            dirty = true
        }
    }
}
