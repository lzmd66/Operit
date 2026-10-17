package com.ai.assistance.operit.data.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 首启预置数据恢复（fork 定制，非上游代码）。
 *
 * 用途：换包（release → .debug）时，把旧包导出的原始快照 zip 预置给新包，做到"装完即用"。
 * 查找顺序：
 *   1) /sdcard/Download/Operit/backup/raw_snapshot/operit_raw_snapshot*.zip（取最新；需要"所有文件访问"）
 *   2) /sdcard/Download/operit_raw_snapshot*.zip（同上，宽容一点）
 *   3) <getExternalFilesDir()>/seed_raw_snapshot.zip（App 自己目录，无需任何权限）
 *   4) assets/seed/raw_snapshot.zip（打进 APK，兜底）
 *
 * 两个关键点：
 *   · 完成标记写 filesDir，**不能写 SharedPreferences** —— restore 会把 shared_prefs 整目录替换成旧包的。
 *   · 每次冷启动都重试，直到成功。但若 filesDir/objectbox 已有内容，视为"用户已配置"，落标记后跳过，
 *     避免把用户新配的数据覆盖掉（恢复只在"新包什么都没配"时才是对的）。
 */
object SeedRestoreInstaller {
    private const val TAG = "SeedRestore"
    private const val MARKER = ".seed_restore_done"
    private const val ASSET_PATH = "seed/raw_snapshot.zip"
    private const val ASSET_FILE = "raw_snapshot.zip"
    private const val SEED_NAME = "seed_raw_snapshot.zip"

    fun installIfNeeded(context: Context) {
        if (File(context.filesDir, MARKER).exists()) return

        if (hasExistingUserData(context)) {
            markDone(context, "skip: existing data")
            return
        }

        val zip = locateSeedZip(context) ?: run {
            AppLogger.i(TAG, "seed restore: no seed zip found")
            return
        }

        AppLogger.i(TAG, "seed restore: start ${zip.absolutePath} (${zip.length()} B)")
        try {
            runBlocking(Dispatchers.IO) {
                // onProgress 传 null：内部就不会切主线程；本函数会在 Application.onCreate 里同步等待
                RawSnapshotBackupManager.restoreFromBackupUri(context, Uri.fromFile(zip))
            }
            markDone(context, "done")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "seed restore: failed", t)
        }
    }

    private fun markDone(context: Context, reason: String) {
        runCatching { File(context.filesDir, MARKER).createNewFile() }
        AppLogger.i(TAG, "seed restore: marked ($reason)")
    }

    private fun hasExistingUserData(context: Context): Boolean {
        val objectbox = File(context.filesDir, "objectbox")
        return objectbox.isDirectory && (objectbox.list()?.isNotEmpty() == true)
    }

    private fun locateSeedZip(context: Context): File? {
        // 1) assets：打进 APK，零权限、零时序依赖，最确定
        runCatching {
            if (context.assets.list("seed")?.contains(ASSET_FILE) == true) {
                val target = File(context.cacheDir, SEED_NAME)
                context.assets.open(ASSET_PATH).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.isFile && target.length() > 0) return target
            }
        }

        // 2) App 自己的 external files 目录（无需权限，可用 adb 投递）
        context.getExternalFilesDir(null)?.let { dir ->
            File(dir, SEED_NAME).takeIf { it.isFile }?.let { return it }
        }

        // 3) Download 目录（需要"所有文件访问"权限）
        val external = Environment.getExternalStorageDirectory()
        val searchDirs = listOf(
            File(external, "Download/Operit/backup/raw_snapshot"),
            File(external, "Download"),
        )
        for (dir in searchDirs) {
            val candidates = dir.listFiles { f ->
                f.isFile && f.name.startsWith("operit_raw_snapshot") && f.name.endsWith(".zip")
            }
            if (!candidates.isNullOrEmpty()) {
                return candidates.maxByOrNull { it.lastModified() }
            }
        }

        return null
    }
}
