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
  const val DEFAULT_GITHUB_REPO = "evandrozacx/Siundeave"

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
      val repoName: String,
      val statusMessage: String = "",
      val statusReason: String = "UNKNOWN"
  )

  data class HttpResponse(val code: Int, val body: String?, val error: String? = null)

  fun cleanRepo(repo: String): String {
    return repo.trim()
        .replace("https://github.com/", "", ignoreCase = true)
        .replace("http://github.com/", "", ignoreCase = true)
        .replace("github.com/", "", ignoreCase = true)
        .replace(".git", "", ignoreCase = true)
        .trim('/')
  }

  fun getGitHubRepo(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val saved = prefs.getString(KEY_GITHUB_REPO, null)
    return if (!saved.isNullOrBlank()) cleanRepo(saved) else DEFAULT_GITHUB_REPO
  }

  fun setGitHubRepo(context: Context, repo: String) {
    val clean = cleanRepo(repo)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_GITHUB_REPO, clean).apply()
  }

  fun getCurrentVersionName(): String = BuildConfig.VERSION_NAME
  fun getCurrentVersionCode(): Int = BuildConfig.VERSION_CODE

  /**
   * Checks GitHub for the latest release or version.json.
   * Provides deep diagnostics for repo existence, release existence, and APK assets.
   */
  fun checkForUpdate(context: Context, customRepo: String? = null): UpdateInfo {
    val rawRepo = customRepo ?: getGitHubRepo(context)
    var repo = cleanRepo(rawRepo)
    val curName = getCurrentVersionName()
    val curCode = getCurrentVersionCode()

    if (repo.isEmpty() || !repo.contains("/")) {
      return UpdateInfo(
          hasUpdate = false,
          currentVersionName = curName,
          currentVersionCode = curCode,
          latestVersionName = curName,
          latestVersionCode = curCode,
          downloadUrl = "",
          changelog = "",
          releaseName = "",
          publishedAt = "",
          repoName = repo,
          statusMessage = "Por favor, informe seu repositório no formato usuario/repositorio (ex: evandrozacx/Siundeave).",
          statusReason = "REPO_INVALID"
      )
    }

    // 0. Verify if repository exists (with fallback candidate probes)
    var repoMetaResp = httpGet("https://api.github.com/repos/$repo")
    if (repoMetaResp.code == 404) {
      // Try alternate candidate names in case of typo (e.g. Siundeave vs SoundWave vs Soundwave)
      val candidates = mutableListOf<String>()
      val owner = repo.substringBefore("/")
      val repoName = repo.substringAfter("/")
      candidates.add("$owner/SoundWave")
      candidates.add("$owner/soundwave")
      candidates.add("$owner/Siundeave")
      candidates.add("$owner/siundeave")
      candidates.add("$owner/Soundwave")

      for (cand in candidates) {
        if (cand.equals(repo, ignoreCase = true)) continue
        val candResp = httpGet("https://api.github.com/repos/$cand")
        if (candResp.code in 200..299) {
          repo = cand
          repoMetaResp = candResp
          setGitHubRepo(context, cand)
          break
        }
      }
    }

    if (repoMetaResp.code == 404) {
      return UpdateInfo(
          hasUpdate = false,
          currentVersionName = curName,
          currentVersionCode = curCode,
          latestVersionName = curName,
          latestVersionCode = curCode,
          downloadUrl = "",
          changelog = "",
          releaseName = "",
          publishedAt = "",
          repoName = repo,
          statusMessage = "O repositório '$repo' não foi encontrado no GitHub (404). Crie o repositório no seu GitHub (https://github.com/new) ou verifique se ele é público.",
          statusReason = "REPO_NOT_FOUND"
      )
    }

    var latestVersion = curName
    var latestCode = curCode
    var downloadUrl = ""
    var changelog = ""
    var releaseName = ""
    var publishedAt = ""
    var hasReleaseObject = false
    var hasApkInRelease = false

    // 1. Try GitHub Releases API
    try {
      val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
      val releaseResp = httpGet(apiUrl)
      if (releaseResp.code in 200..299 && !releaseResp.body.isNullOrEmpty()) {
        hasReleaseObject = true
        val json = JSONObject(releaseResp.body)
        val tagName = json.optString("tag_name", "").removePrefix("v").trim()
        releaseName = json.optString("name", "SoundWave $tagName")
        changelog = json.optString("body", "")
        publishedAt = json.optString("published_at", "")

        if (tagName.isNotEmpty()) latestVersion = tagName

        // Search for .apk asset in release
        val assets = json.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until assets.length()) {
          val asset = assets.getJSONObject(i)
          val name = asset.optString("name", "")
          if (name.endsWith(".apk", ignoreCase = true)) {
            downloadUrl = asset.optString("browser_download_url", "")
            hasApkInRelease = true
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
          val rawResp = httpGet(rawUrl)
          if (rawResp.code in 200..299 && !rawResp.body.isNullOrEmpty()) {
            val rawJson = JSONObject(rawResp.body)
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
              } else {
                // Try if soundwave.apk or specified apkName exists in raw repo
                val apkName = rawJson.optString("apkName", "soundwave.apk")
                val probeApkUrl = "https://raw.githubusercontent.com/$repo/main/$apkName"
                val apkHeadResp = httpGet(probeApkUrl)
                if (apkHeadResp.code in 200..299) {
                  downloadUrl = probeApkUrl
                }
              }
              break
            }
          }
        }
      } catch (e: Exception) {
        Log.d(TAG, "Raw version.json fetch failed: ${e.message}")
      }
    }

    // Determine status reason & user-friendly message
    val isNewer = isVersionGreater(latestVersion, curName) || (latestCode > curCode)
    val hasUpdate = isNewer && downloadUrl.isNotEmpty()

    val (statusReason, statusMessage) = when {
      hasUpdate -> {
        Pair("UPDATE_AVAILABLE", "🚀 Nova versão v$latestVersion disponível para atualização imediata!")
      }
      hasReleaseObject && !hasApkInRelease -> {
        Pair(
            "NO_APK_IN_RELEASE",
            "Release 'v$latestVersion' encontrada no GitHub, mas nenhum arquivo .apk foi anexado a ela. Arraste o APK para a Release no GitHub para que os usuários possam atualizar."
        )
      }
      !hasReleaseObject && downloadUrl.isEmpty() -> {
        Pair(
            "NO_RELEASES",
            "O código está no GitHub, mas nenhuma Release pública foi criada ainda. Para o app atualizar sozinho, crie uma Release (ex: v1.2.0) e anexe o arquivo .apk nela."
        )
      }
      !isNewer -> {
        Pair(
            "ALREADY_UPDATED",
            "Você já está na versão mais recente instalada (v$curName). Para disparar uma atualização para os usuários, aumente o número da versão (ex: v1.2.0) e crie uma nova Release com o APK."
        )
      }
      else -> {
        Pair("NO_DOWNLOAD_URL", "Versão v$latestVersion encontrada, mas o link direto do APK não está disponível.")
      }
    }

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
        repoName = repo,
        statusMessage = statusMessage,
        statusReason = statusReason
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

  fun httpGet(urlStr: String): HttpResponse {
    var conn: HttpURLConnection? = null
    return try {
      val url = URL(urlStr)
      conn = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 SoundWave-Updater/1.1")
        setRequestProperty("Accept", "application/vnd.github.v3+json, application/json, */*")
      }
      val code = conn.responseCode
      if (code in 200..299) {
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        HttpResponse(code, text)
      } else {
        HttpResponse(code, null, "HTTP $code")
      }
    } catch (e: Exception) {
      Log.d(TAG, "HTTP GET failed for $urlStr: ${e.message}")
      HttpResponse(-1, null, e.message)
    } finally {
      conn?.disconnect()
    }
  }
}
