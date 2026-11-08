package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object AppUpdateManager {
  private const val TAG = "AppUpdateManager"
  private const val PREFS_NAME = "soundwave_prefs"
  private const val KEY_GITHUB_REPO = "github_update_repo"

  // Default repository fallback if none entered yet
  const val DEFAULT_GITHUB_REPO = "comercialemds/SoundWave"

  var pendingApkPath: String? = null

  data class UpdateInfo(
      val hasUpdate: Boolean,
      val currentVersionName: String,
      val currentVersionCode: Int,
      val latestVersionName: String,
      val latestVersionCode: Int,
      val downloadUrl: String,
      val changelog: String,
      val releaseName: String,
      val publishedAt: String,
      val repoName: String
  )

  fun getGitHubRepo(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val saved = prefs.getString(KEY_GITHUB_REPO, null)
    return if (!saved.isNullOrBlank()) saved.trim() else DEFAULT_GITHUB_REPO
  }

  fun setGitHubRepo(context: Context, repo: String) {
    val clean = repo.trim().removePrefix("https://github.com/").removeSuffix(".git")
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_GITHUB_REPO, clean).apply()
  }

  fun getCurrentVersionName(): String = BuildConfig.VERSION_NAME
  fun getCurrentVersionCode(): Int = BuildConfig.VERSION_CODE

  /**
   * Checks GitHub for the latest release or version.json.
   * Handles both GitHub Releases API and Raw version.json fallback.
   */
  fun checkForUpdate(context: Context, customRepo: String? = null): UpdateInfo {
    val repo = (customRepo ?: getGitHubRepo(context)).trim().removePrefix("https://github.com/").removeSuffix(".git")
    val curName = getCurrentVersionName()
    val curCode = getCurrentVersionCode()

    var latestVersion = curName
    var latestCode = curCode
    var downloadUrl = ""
    var changelog = ""
    var releaseName = ""
    var publishedAt = ""

    // 1. Try GitHub Releases API first
    try {
      val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
      val jsonStr = httpGet(apiUrl)
      if (!jsonStr.isNullOrEmpty()) {
        val json = JSONObject(jsonStr)
        val tagName = json.optString("tag_name", "").removePrefix("v").trim()
        releaseName = json.optString("name", "SoundWave $tagName")
        changelog = json.optString("body", "")
        publishedAt = json.optString("published_at", "")

        latestVersion = if (tagName.isNotEmpty()) tagName else curName

        // Search for .apk asset in release
        val assets = json.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until assets.length()) {
          val asset = assets.getJSONObject(i)
          val name = asset.optString("name", "")
          if (name.endsWith(".apk", ignoreCase = true)) {
            downloadUrl = asset.optString("browser_download_url", "")
            break
          }
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "GitHub Releases API failed: ${e.message}")
    }

    // 2. Fallback: try raw version.json from main or master branch
    if (downloadUrl.isEmpty()) {
      try {
        val rawUrls = listOf(
            "https://raw.githubusercontent.com/$repo/main/version.json",
            "https://raw.githubusercontent.com/$repo/master/version.json"
        )
        for (rawUrl in rawUrls) {
          val rawJsonStr = httpGet(rawUrl)
          if (!rawJsonStr.isNullOrEmpty()) {
            val rawJson = JSONObject(rawJsonStr)
            val vName = rawJson.optString("versionName", "").removePrefix("v").trim()
            val vCode = rawJson.optInt("versionCode", 0)
            if (vName.isNotEmpty()) {
              latestVersion = vName
              latestCode = if (vCode > 0) vCode else latestCode
              if (releaseName.isEmpty()) releaseName = rawJson.optString("releaseName", "SoundWave v$vName")
              if (changelog.isEmpty()) changelog = rawJson.optString("changelog", "")
              if (publishedAt.isEmpty()) publishedAt = rawJson.optString("releaseDate", "")
              val rawApkUrl = rawJson.optString("apkUrl", "")
              if (rawApkUrl.isNotEmpty()) {
                downloadUrl = rawApkUrl
              }
              break
            }
          }
        }
      } catch (e: Exception) {
        Log.d(TAG, "Raw version.json fetch failed: ${e.message}")
      }
    }

    // Determine if update is available
    val isNewer = isVersionGreater(latestVersion, curName) || (latestCode > curCode)
    val hasUpdate = isNewer && downloadUrl.isNotEmpty()

    return UpdateInfo(
        hasUpdate = hasUpdate,
        currentVersionName = curName,
        currentVersionCode = curCode,
        latestVersionName = latestVersion,
        latestVersionCode = latestCode,
        downloadUrl = downloadUrl,
        changelog = changelog.ifEmpty { "Novas melhorias e correções no SoundWave." },
        releaseName = releaseName.ifEmpty { "SoundWave v$latestVersion" },
        publishedAt = publishedAt,
        repoName = repo
    )
  }

  /**
   * Compares two semantic version strings (e.g. "1.1.0" vs "1.0.0").
   */
  fun isVersionGreater(newVer: String, currentVer: String): Boolean {
    val cleanNew = newVer.trim().removePrefix("v")
    val cleanCur = currentVer.trim().removePrefix("v")
    if (cleanNew.isEmpty() || cleanCur.isEmpty()) return false
    if (cleanNew == cleanCur) return false

    val newParts = cleanNew.split(".").mapNotNull { it.toIntOrNull() }
    val curParts = cleanCur.split(".").mapNotNull { it.toIntOrNull() }

    val maxLen = maxOf(newParts.size, curParts.size)
    for (i in 0 until maxLen) {
      val n = newParts.getOrElse(i) { 0 }
      val c = curParts.getOrElse(i) { 0 }
      if (n > c) return true
      if (n < c) return false
    }
    return false
  }

  /**
   * Downloads the APK file to app cache/download directory and triggers installation.
   */
  fun downloadAndInstall(
      activity: Activity,
      apkUrl: String,
      onProgress: (Int) -> Unit,
      onSuccess: () -> Unit,
      onError: (String) -> Unit
  ) {
    try {
      val targetDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
      if (!targetDir.exists()) targetDir.mkdirs()
      val apkFile = File(targetDir, "soundwave_update.apk")
      if (apkFile.exists()) apkFile.delete()

      var connection: HttpURLConnection? = null
      var currentUrl = apkUrl

      // Follow potential redirects
      for (redirectCount in 0..5) {
        val url = URL(currentUrl)
        connection = (url.openConnection() as HttpURLConnection).apply {
          connectTimeout = 15000
          readTimeout = 20000
          instanceFollowRedirects = true
          setRequestProperty(
              "User-Agent",
              "Mozilla/5.0 (Linux; Android 14; Mobile) SoundWaveUpdater/1.1"
          )
        }
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_MOVED_PERM ||
            code == HttpURLConnection.HTTP_MOVED_TEMP ||
            code == 307 || code == 308) {
          val loc = connection.getHeaderField("Location")
          if (!loc.isNullOrEmpty()) {
            currentUrl = loc
            connection.disconnect()
            continue
          }
        }
        break
      }

      val conn = connection ?: throw IllegalStateException("Falha ao abrir conexão")
      val contentLength = conn.contentLength
      var bytesDownloaded = 0L

      conn.inputStream.use { input ->
        FileOutputStream(apkFile).use { output ->
          val buffer = ByteArray(8192)
          var read: Int
          var lastPercent = -1
          while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            bytesDownloaded += read
            if (contentLength > 0) {
              val percent = ((bytesDownloaded * 100) / contentLength).toInt().coerceIn(0, 100)
              if (percent != lastPercent) {
                lastPercent = percent
                activity.runOnUiThread { onProgress(percent) }
              }
            }
          }
        }
      }

      activity.runOnUiThread {
        onProgress(100)
        onSuccess()
        installApk(activity, apkFile)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error downloading update APK: ${e.message}", e)
      activity.runOnUiThread { onError(e.message ?: "Erro desconhecido ao baixar atualização") }
    }
  }

  /**
   * Prompts the Android Package Installer for the downloaded APK using FileProvider.
   */
  fun installApk(activity: Activity, apkFile: File) {
    if (!apkFile.exists()) {
      Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
      return
    }

    // On Android 8.0+ (API 26), verify if app can request package installations
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (!activity.packageManager.canRequestPackageInstalls()) {
        pendingApkPath = apkFile.absolutePath
        try {
          val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${activity.packageName}")
          }
          activity.startActivity(intent)
        } catch (_: Exception) {
          val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
          activity.startActivity(intent)
        }
        return
      }
    }

    try {
      val apkUri = FileProvider.getUriForFile(
          activity,
          "${activity.packageName}.fileprovider",
          apkFile
      )

      val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      activity.startActivity(installIntent)
      pendingApkPath = null
    } catch (e: Exception) {
      Log.e(TAG, "Error launching package installer: ${e.message}", e)
    }
  }

  fun resumePendingInstallIfAllowed(activity: Activity) {
    val path = pendingApkPath ?: return
    val file = File(path)
    if (!file.exists()) {
      pendingApkPath = null
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (activity.packageManager.canRequestPackageInstalls()) {
        installApk(activity, file)
      }
    } else {
      installApk(activity, file)
    }
  }

  private fun httpGet(urlStr: String): String? {
    var conn: HttpURLConnection? = null
    return try {
      val url = URL(urlStr)
      conn = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "SoundWave-Updater")
        setRequestProperty("Accept", "application/vnd.github.v3+json, application/json")
      }
      if (conn.responseCode in 200..299) {
        conn.inputStream.bufferedReader().use { it.readText() }
      } else {
        null
      }
    } catch (e: Exception) {
      Log.d(TAG, "HTTP GET failed for $urlStr: ${e.message}")
      null
    } finally {
      conn?.disconnect()
    }
  }
}
