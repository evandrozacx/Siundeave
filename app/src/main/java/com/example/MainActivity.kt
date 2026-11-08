package com.example

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.graphics.Bitmap

class MainActivity : ComponentActivity() {

  companion object {
    var instance: MainActivity? = null
      private set
  }

  var currentTrackTitle: String = "SoundWave"
  var currentTrackArtist: String = "Tocando agora"
  var isAudioPlaying: Boolean = false
  var currentCoverUrl: String = ""

  private var webView: WebView? = null
  private lateinit var container: FrameLayout
  private var filePathCallback: ValueCallback<Array<Uri>>? = null
  private val torrentBridge = AndroidTorrentBridge()
  private val failedEmbeddedArtCache = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  private var audioManager: android.media.AudioManager? = null
  private var audioFocusRequest: android.media.AudioFocusRequest? = null
  private var resumeOnFocusGain = false

  private val audioFocusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      android.media.AudioManager.AUDIOFOCUS_LOSS -> {
        resumeOnFocusGain = false
        if (torrentBridge.isNativeAudioPlaying()) {
          runOnUiThread {
            pauseNativePlaybackInternal()
          }
        }
      }
      android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        if (torrentBridge.isNativeAudioPlaying()) {
          resumeOnFocusGain = true
          runOnUiThread {
            pauseNativePlaybackInternal()
          }
        }
      }
      android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        runOnUiThread {
          try {
            torrentBridge.setVolumeNative(0.2f)
          } catch (_: Exception) {}
        }
      }
      android.media.AudioManager.AUDIOFOCUS_GAIN -> {
        runOnUiThread {
          try {
            torrentBridge.setVolumeNative(1.0f)
          } catch (_: Exception) {}
          if (resumeOnFocusGain) {
            resumeOnFocusGain = false
            resumeNativePlaybackInternal()
          }
        }
      }
    }
  }

  fun requestAudioFocus(): Boolean {
    if (audioManager == null) {
      audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    }
    val am = audioManager ?: return true

    val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(
              android.media.AudioAttributes.Builder()
                  .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                  .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                  .build()
          )
          .setAcceptsDelayedFocusGain(true)
          .setOnAudioFocusChangeListener(audioFocusChangeListener)
          .build()
      audioFocusRequest = focusRequest
      am.requestAudioFocus(focusRequest)
    } else {
      @Suppress("DEPRECATION")
      am.requestAudioFocus(
          audioFocusChangeListener,
          android.media.AudioManager.STREAM_MUSIC,
          android.media.AudioManager.AUDIOFOCUS_GAIN
      )
    }
    return result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  fun abandonAudioFocus() {
    val am = audioManager ?: return
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
      } else {
        @Suppress("DEPRECATION")
        am.abandonAudioFocus(audioFocusChangeListener)
      }
    } catch (_: Exception) {}
  }

  private fun pauseNativePlaybackInternal() {
    try {
      if (torrentBridge.isNativeAudioPlaying()) {
        torrentBridge.pauseAudioNative()
        webView?.evaluateJavascript(
            "if (typeof activeAudioMode !== 'undefined' && activeAudioMode === 'native' && typeof AppState !== 'undefined') { AppState.isPlaying = false; if (typeof updatePlayIcons === 'function') updatePlayIcons(false); }",
            null)
      }
    } catch (e: Exception) {
      Log.w("SoundWave", "Error pausing playback on focus loss: ${e.message}")
    }
  }

  private fun resumeNativePlaybackInternal() {
    try {
      if (requestAudioFocus()) {
        torrentBridge.resumeAudioNative()
        webView?.evaluateJavascript(
            "if (typeof activeAudioMode !== 'undefined' && activeAudioMode === 'native' && typeof AppState !== 'undefined') { AppState.isPlaying = true; if (typeof updatePlayIcons === 'function') updatePlayIcons(true); }",
            null)
      }
    } catch (e: Exception) {
      Log.w("SoundWave", "Error resuming playback on focus gain: ${e.message}")
    }
  }

  private val okHttpClient =
      OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .followRedirects(true)
          .build()

  fun pruneAudioStreamCache(maxItems: Int = 30) {
    try {
      val audioFiles = cacheDir.listFiles { _, name ->
        name.startsWith("audio_full_")
      }?.sortedBy { it.lastModified() } ?: return
      if (audioFiles.size > maxItems) {
        val toDelete = audioFiles.take(audioFiles.size - maxItems)
        for (file in toDelete) {
          file.delete()
        }
      }
    } catch (e: Exception) {
      Log.w("SoundWave", "Error pruning audio cache: ${e.message}")
    }
  }

  fun cacheStreamAudioInBackground(url: String, targetFile: File) {
    if (url.startsWith("magnet:") || url.contains("soundwave.app/yt_embed")) return
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        if (targetFile.exists() && targetFile.length() > 50000) return@launch
        val tempFile = File(cacheDir, targetFile.name + ".tmp")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
          if (resp.isSuccessful) {
            val body = resp.body ?: return@launch
            FileOutputStream(tempFile).use { out ->
              body.byteStream().copyTo(out)
            }
            if (tempFile.exists() && tempFile.length() > 30000) {
              tempFile.renameTo(targetFile)
              targetFile.setLastModified(System.currentTimeMillis())
              pruneAudioStreamCache(30)
            } else {
              tempFile.delete()
            }
          }
        }
      } catch (_: Exception) {
        // Background stream caching is non-fatal
      }
    }
  }

  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
          Log.w("SoundWave", "Notification permission not granted")
        }
      }

  private val mediaPermissionLauncher: ActivityResultLauncher<Array<String>> =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val isGranted = permissions.values.any { it }
        if (isGranted) {
          scanDeviceMediaStore(false)
        } else {
          Toast.makeText(
                  this,
                  "Permissão necessária para listar suas músicas. Conceda acesso nas configurações.",
                  Toast.LENGTH_LONG)
              .show()
        }
      }

  fun requestStoragePermissionsAndScan(isSyncMode: Boolean = false) {
    val permissionsToRequest = mutableListOf<String>()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) !=
          android.content.pm.PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_AUDIO)
      }
    } else {
      if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
          android.content.pm.PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
      }
    }

    if (permissionsToRequest.isEmpty()) {
      scanDeviceMediaStore(isSyncMode)
    } else {
      mediaPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }
  }

  fun scanDeviceMediaStore(isSyncMode: Boolean = false) {
    lifecycleScope.launch(Dispatchers.IO) {
      val jsonArray = org.json.JSONArray()
      val seenUris = mutableSetOf<String>()
      val folderImageCache = mutableMapOf<String, String>() // bucketId or relativePath -> coverUrl

      // 0. Pre-scan MediaStore.Images and MediaStore.Files for loose image files in folders
      try {
        val imgProj = mutableListOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.BUCKET_ID,
            android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          imgProj.add(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
        }

        val imagesUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(imagesUri, imgProj.toTypedArray(), null, null, null)?.use { imgCursor ->
          val idCol = imgCursor.getColumnIndex(android.provider.MediaStore.Images.Media._ID)
          val nameCol = imgCursor.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
          val bucketIdCol = imgCursor.getColumnIndex(android.provider.MediaStore.Images.Media.BUCKET_ID)
          val bucketNameCol = imgCursor.getColumnIndex(android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
          val relPathCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            imgCursor.getColumnIndex(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
          } else -1

          while (imgCursor.moveToNext()) {
            val imgId = if (idCol != -1) imgCursor.getLong(idCol) else 0L
            if (imgId <= 0) continue
            val name = (if (nameCol != -1) imgCursor.getString(nameCol) ?: "" else "").lowercase()
            val bucketId = if (bucketIdCol != -1) imgCursor.getString(bucketIdCol) ?: "" else ""
            val bucketName = (if (bucketNameCol != -1) imgCursor.getString(bucketNameCol) ?: "" else "").trim()
            val relPath = (if (relPathCol != -1) imgCursor.getString(relPathCol) ?: "" else "").trim()

            val imgUri = android.content.ContentUris.withAppendedId(imagesUri, imgId)
            val coverUrl = "https://soundwave.local/cover?uri=" + java.net.URLEncoder.encode(imgUri.toString(), "UTF-8")

            val isPriorityCover = name.contains("cover") || name.contains("folder") || name.contains("album") ||
                name.contains("front") || name.contains("capa") || name.contains("art") || name.contains("thumb")

            if (bucketId.isNotEmpty() && (isPriorityCover || !folderImageCache.containsKey("bucket_$bucketId"))) {
              folderImageCache["bucket_$bucketId"] = coverUrl
            }
            if (bucketName.isNotEmpty() && (isPriorityCover || !folderImageCache.containsKey("name_${bucketName.lowercase()}"))) {
              folderImageCache["name_${bucketName.lowercase()}"] = coverUrl
            }
            if (relPath.isNotEmpty() && (isPriorityCover || !folderImageCache.containsKey("path_${relPath.lowercase()}"))) {
              folderImageCache["path_${relPath.lowercase()}"] = coverUrl
            }
          }
        }
      } catch (e: Exception) {
        Log.w("SoundWaveMediaStore", "Error pre-scanning loose images: ${e.message}")
      }

      // 1. Query MediaStore.Audio (both external and internal)
      val projectionList = mutableListOf(
          android.provider.MediaStore.Audio.Media._ID,
          android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
          android.provider.MediaStore.Audio.Media.TITLE,
          android.provider.MediaStore.Audio.Media.ARTIST,
          android.provider.MediaStore.Audio.Media.ALBUM,
          android.provider.MediaStore.Audio.Media.SIZE,
          android.provider.MediaStore.Audio.Media.DURATION,
          android.provider.MediaStore.Audio.Media.BUCKET_ID,
          android.provider.MediaStore.Audio.Media.BUCKET_DISPLAY_NAME
      )
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        projectionList.add(android.provider.MediaStore.Audio.Media.RELATIVE_PATH)
      }
      val projection = projectionList.toTypedArray()

      val contentUris = listOf(
          android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
          android.provider.MediaStore.Audio.Media.INTERNAL_CONTENT_URI
      )

      for (contentUri in contentUris) {
        try {
          contentResolver.query(
              contentUri,
              projection,
              null,
              null,
              "${android.provider.MediaStore.Audio.Media.TITLE} ASC"
          )?.use { cursor ->
            val idCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.ALBUM)
            val sizeCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.SIZE)
            val durationCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DURATION)
            val bucketIdCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            val relPathCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
              cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext() && jsonArray.length() < 3000) {
              val id = if (idCol != -1) cursor.getLong(idCol) else 0L
              if (id <= 0) continue

              val itemUri = android.content.ContentUris.withAppendedId(contentUri, id)
              val uriStr = itemUri.toString()
              if (!seenUris.add(uriStr)) continue

              val displayName = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
              val title = if (titleCol != -1) cursor.getString(titleCol) ?: "" else ""
              val artist = if (artistCol != -1) cursor.getString(artistCol) ?: "" else ""
              val album = if (albumCol != -1) cursor.getString(albumCol) ?: "" else ""
              val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
              val durationMs = if (durationCol != -1) cursor.getLong(durationCol) else 0L
              val bucketId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) ?: "" else ""
              val bucketName = (if (bucketNameCol != -1) cursor.getString(bucketNameCol) ?: "" else "").trim()
              val relPath = (if (relPathCol != -1) cursor.getString(relPathCol) ?: "" else "").trim()

              if (size in 1..4999 && durationMs in 1..2000) continue

              val folderName = when {
                bucketName.isNotEmpty() && bucketName != "0" -> bucketName
                album.isNotEmpty() && album != "<unknown>" -> album
                else -> "Memória Interna"
              }
              val finalTitle = if (title.isNotEmpty() && title != "<unknown>") title else displayName.replace(Regex("\\.[^/.]+$"), "")
              val finalArtist = if (artist.isNotEmpty() && artist != "<unknown>") artist else "Arquivo Local"

              val looseFolderCover = folderImageCache["bucket_$bucketId"]
                  ?: folderImageCache["path_${relPath.lowercase()}"]
                  ?: folderImageCache["name_${folderName.lowercase()}"]
                  ?: folderImageCache["name_${album.lowercase()}"]

              val audioCoverFallback = "https://soundwave.local/audio_cover?uri=" + java.net.URLEncoder.encode(uriStr, "UTF-8")
              val obj = org.json.JSONObject().apply {
                put("id", id)
                put("name", displayName.ifEmpty { "$finalTitle.mp3" })
                put("title", finalTitle)
                put("artist", finalArtist)
                put("album", if (album.isNotEmpty() && album != "<unknown>") album else folderName)
                put("parentFolder", folderName)
                put("uri", uriStr)
                put("coverUrl", looseFolderCover ?: audioCoverFallback)
                put("size", size)
                put("duration", Math.round(durationMs / 1000.0))
              }
              jsonArray.put(obj)
            }
          }
        } catch (e: Exception) {
          Log.w("SoundWaveMediaStore", "Error querying $contentUri: ${e.message}")
        }
      }

      // 2. Also search MediaStore.Files for all audio extensions (catches Downloads, WhatsApp, Telegram, etc.)
      try {
        val filesUri = android.provider.MediaStore.Files.getContentUri("external")
        val filesProj = mutableListOf(
            android.provider.MediaStore.Files.FileColumns._ID,
            android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
            android.provider.MediaStore.Files.FileColumns.TITLE,
            android.provider.MediaStore.Files.FileColumns.SIZE,
            android.provider.MediaStore.Files.FileColumns.BUCKET_ID,
            android.provider.MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          filesProj.add(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH)
        }
        val selection = "${android.provider.MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'audio/%' OR " +
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.mp3' OR " +
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.flac' OR " +
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.wav' OR " +
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.m4a' OR " +
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.aac' OR " +
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.ogg' OR " +
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.opus'"

        contentResolver.query(filesUri, filesProj.toTypedArray(), selection, null, null)?.use { cursor ->
          val idCol = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns._ID)
          val nameCol = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
          val titleCol = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.TITLE)
          val sizeCol = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.SIZE)
          val bucketIdCol = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.BUCKET_ID)
          val bucketNameCol = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
          val relPathCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH)
          } else -1

          while (cursor.moveToNext() && jsonArray.length() < 3000) {
            val id = if (idCol != -1) cursor.getLong(idCol) else 0L
            if (id <= 0) continue

            val itemUri = android.content.ContentUris.withAppendedId(filesUri, id)
            val uriStr = itemUri.toString()
            if (!seenUris.add(uriStr)) continue

            val displayName = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
            val title = if (titleCol != -1) cursor.getString(titleCol) ?: "" else ""
            val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
            val bucketId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) ?: "" else ""
            val bucketName = (if (bucketNameCol != -1) cursor.getString(bucketNameCol) ?: "" else "").trim()
            val relPath = (if (relPathCol != -1) cursor.getString(relPathCol) ?: "" else "").trim()

            val folderName = if (bucketName.isNotEmpty() && bucketName != "0") bucketName else "Downloads & Arquivos"
            val finalTitle = if (title.isNotEmpty() && title != "<unknown>") title else displayName.replace(Regex("\\.[^/.]+$"), "")

            val looseFolderCover = folderImageCache["bucket_$bucketId"]
                ?: folderImageCache["path_${relPath.lowercase()}"]
                ?: folderImageCache["name_${folderName.lowercase()}"]

            val audioCoverFallback = "https://soundwave.local/audio_cover?uri=" + java.net.URLEncoder.encode(uriStr, "UTF-8")
            val obj = org.json.JSONObject().apply {
              put("id", id)
              put("name", displayName.ifEmpty { "$finalTitle.mp3" })
              put("title", finalTitle)
              put("artist", "Arquivo Local")
              put("album", folderName)
              put("parentFolder", folderName)
              put("uri", uriStr)
              put("coverUrl", looseFolderCover ?: audioCoverFallback)
              put("size", size)
              put("duration", 0)
            }
            jsonArray.put(obj)
          }
        }
      } catch (e: Exception) {
        Log.w("SoundWaveFilesStore", "Error querying files: ${e.message}")
      }

      Log.d("SoundWaveMediaStore", "Total audio tracks found: ${jsonArray.length()}")

      withContext(Dispatchers.Main) {
        if (jsonArray.length() == 0) {
          Toast.makeText(this@MainActivity, "Nenhuma música encontrada. Selecione a pasta das suas músicas.", Toast.LENGTH_LONG).show()
        }
        val quoted = JSONObject.quote(jsonArray.toString())
        webView?.evaluateJavascript(
            "if (window.onNativeFolderScanned) { window.onNativeFolderScanned($quoted, $isSyncMode); }",
            null)
      }
    }
  }

  private val audioFilePickerLauncher: ActivityResultLauncher<Intent> =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
          val data = result.data ?: return@registerForActivityResult
          val selectedUris = mutableListOf<Uri>()
          val clipData = data.clipData
          if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
              selectedUris.add(clipData.getItemAt(i).uri)
            }
          } else if (data.data != null) {
            selectedUris.add(data.data!!)
          }

          if (selectedUris.isEmpty()) return@registerForActivityResult

          lifecycleScope.launch(Dispatchers.IO) {
            val jsonArray = org.json.JSONArray()
            val audioItems = mutableListOf<Triple<Uri, String, Long>>()
            val imageMap = mutableMapOf<String, String>() // name to uriStr

            for (uri in selectedUris) {
              try {
                try {
                  contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}

                var name = ""
                var size = 0L
                var mime = contentResolver.getType(uri) ?: ""

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                  if (cursor.moveToFirst()) {
                    val nIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nIdx != -1) name = cursor.getString(nIdx) ?: ""
                    if (sIdx != -1) size = cursor.getLong(sIdx)
                  }
                }
                if (name.isEmpty()) name = uri.lastPathSegment ?: "file"

                if (mime.startsWith("audio/") || name.matches(Regex(".*\\.(mp3|flac|wav|m4a|aac|ogg|opus)$", RegexOption.IGNORE_CASE))) {
                  audioItems.add(Triple(uri, name, size))
                } else if (mime.startsWith("image/") || name.matches(Regex(".*\\.(jpg|jpeg|png|webp|bmp|gif|jfif|heic)$", RegexOption.IGNORE_CASE))) {
                  imageMap[name.lowercase()] = uri.toString()
                }
              } catch (_: Exception) {}
            }

            // Melhor capa do lote
            var batchCoverUrl: String? = null
            if (imageMap.isNotEmpty()) {
              val bestKey = imageMap.keys.find {
                it.contains("cover") || it.contains("folder") || it.contains("album") ||
                    it.contains("front") || it.contains("capa") || it.contains("art")
              } ?: imageMap.keys.first()
              val coverUri = imageMap[bestKey]!!
              batchCoverUrl = "https://soundwave.local/cover?uri=" + java.net.URLEncoder.encode(coverUri, "UTF-8")
            }

            for ((uri, displayName, fileSize) in audioItems) {
              try {
                var title = ""
                var artist = ""
                var album = ""
                var durationSec = 0L

                val mmr = android.media.MediaMetadataRetriever()
                try {
                  mmr.setDataSource(applicationContext, uri)
                  title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                  artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                  album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                  val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                  if (!durStr.isNullOrEmpty()) {
                    durationSec = Math.round(durStr.toLong() / 1000.0)
                  }
                } catch (_: Exception) {
                } finally {
                  try { mmr.release() } catch (_: Exception) {}
                }

                val finalTitle = title.ifEmpty { displayName.replace(Regex("\\.[^/.]+$"), "") }
                val finalArtist = artist.ifEmpty { "Arquivo Local" }
                val finalFolder = album.ifEmpty { "Arquivos Selecionados" }
                val audioCoverFallback = "https://soundwave.local/audio_cover?uri=" + java.net.URLEncoder.encode(uri.toString(), "UTF-8")

                val obj = org.json.JSONObject().apply {
                  put("id", Math.abs(uri.toString().hashCode()))
                  put("name", displayName)
                  put("title", finalTitle)
                  put("artist", finalArtist)
                  put("album", finalFolder)
                  put("parentFolder", finalFolder)
                  put("uri", uri.toString())
                  put("coverUrl", batchCoverUrl ?: audioCoverFallback)
                  put("size", fileSize)
                  put("duration", durationSec)
                }
                jsonArray.put(obj)
              } catch (e: Exception) {
                Log.w("SoundWaveFilePicker", "Error processing uri $uri: ${e.message}")
              }
            }

            withContext(Dispatchers.Main) {
              if (jsonArray.length() > 0) {
                val quoted = JSONObject.quote(jsonArray.toString())
                webView?.evaluateJavascript(
                    "if (window.onNativeFolderScanned) { window.onNativeFolderScanned($quoted, false); }",
                    null)
              } else {
                Toast.makeText(this@MainActivity, "Nenhum arquivo de áudio pôde ser adicionado.", Toast.LENGTH_SHORT).show()
              }
            }
          }
        }
      }

  private val fileChooserLauncher: ActivityResultLauncher<Intent> =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback != null) {
          val results: Array<Uri>? =
              if (result.resultCode == RESULT_OK) {
                val data = result.data
                if (data != null) {
                  val clipData = data.clipData
                  if (clipData != null) {
                    val count = clipData.itemCount
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until count) {
                      val u = clipData.getItemAt(i).uri
                      try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                      uris.add(u)
                    }
                    uris.toTypedArray()
                  } else if (data.data != null) {
                    val u = data.data!!
                    try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    arrayOf(u)
                  } else null
                } else null
              } else null

          filePathCallback?.onReceiveValue(results)
          filePathCallback = null
        }
      }

  private val folderChooserLauncher: ActivityResultLauncher<Intent> =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
          val treeUri: Uri = result.data?.data ?: return@registerForActivityResult
          try {
            contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_folder_uri", treeUri.toString())
                .apply()
          } catch (_: Exception) {}

          lifecycleScope.launch(Dispatchers.IO) {
            val jsonArray = scanFolderTree(treeUri)
            withContext(Dispatchers.Main) {
              if (jsonArray.length() == 0) {
                Toast.makeText(this@MainActivity, "Varrendo todo o armazenamento do celular...", Toast.LENGTH_SHORT).show()
                requestStoragePermissionsAndScan(false)
              } else {
                val quoted = JSONObject.quote(jsonArray.toString())
                webView?.evaluateJavascript(
                    "if (window.onNativeFolderScanned) { window.onNativeFolderScanned($quoted, false); }",
                    null)
              }
            }
          }
        }
      }

  private fun scanFolderTree(treeUri: Uri): org.json.JSONArray {
    val jsonArray = org.json.JSONArray()
    try {
      val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
      var rootName = ""
      try {
        val decoded = Uri.decode(treeUri.toString())
        if (decoded.contains(":")) rootName = decoded.substringAfterLast(":")
      } catch (_: Exception) {}
      scanDirRecursive(treeUri, docId, rootName, jsonArray, null)
    } catch (e: Exception) {
      Log.w("SoundWaveFolderScanner", "Error scanning folder tree: ${e.message}")
    }
    return jsonArray
  }

  private fun findFirstCoverInSubdirs(
      treeUri: Uri,
      subdirs: List<Pair<String, String>>
  ): String? {
    // Se a pasta tiver subpasta como "Artwork", "Covers", "Capas", "Scans" ou similar, busca imagem lá dentro
    val artSubdirs = subdirs.sortedByDescending { (_, name) ->
      val n = name.lowercase()
      if (n.contains("cover") || n.contains("art") || n.contains("capa") || n.contains("scan") || n.contains("image") || n.contains("img")) 1 else 0
    }
    for ((subId, _) in artSubdirs) {
      try {
        val subChildrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, subId)
        val proj = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(subChildrenUri, proj, null, null, null)?.use { c ->
          val idIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
          val nameIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
          val mimeIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
          val foundImages = mutableListOf<Pair<String, String>>()
          while (c.moveToNext()) {
            val cid = c.getString(idIdx) ?: continue
            val cname = c.getString(nameIdx) ?: continue
            val cmime = c.getString(mimeIdx) ?: ""
            if (cmime.startsWith("image/") || cname.matches(Regex(".*\\.(jpg|jpeg|png|webp|bmp|gif|jfif|heic)$", RegexOption.IGNORE_CASE))) {
              foundImages.add(cid to cname)
            }
          }
          if (foundImages.isNotEmpty()) {
            val best = foundImages.find { (_, name) ->
              val n = name.lowercase()
              n.contains("cover") || n.contains("folder") || n.contains("album") || n.contains("front") || n.contains("capa") || n.contains("art")
            } ?: foundImages.first()
            val coverDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, best.first)
            return "https://soundwave.local/cover?uri=" + java.net.URLEncoder.encode(coverDocUri.toString(), "UTF-8")
          }
        }
      } catch (_: Exception) {}
    }
    return null
  }

  private fun scanDirRecursive(
      treeUri: Uri,
      parentDocId: String,
      currentPath: String,
      jsonArray: org.json.JSONArray,
      inheritedCoverUrl: String? = null
  ) {
    if (jsonArray.length() >= 2000) return
    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
        android.provider.DocumentsContract.Document.COLUMN_SIZE
    )

    try {
      contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)

        val audioItems = mutableListOf<Triple<String, String, Long>>()
        val subdirs = mutableListOf<Pair<String, String>>()
        val imageItems = mutableListOf<Pair<String, String>>()

        while (cursor.moveToNext()) {
          val childId = cursor.getString(idIndex) ?: continue
          val displayName = cursor.getString(nameIndex) ?: continue
          val mimeType = cursor.getString(mimeIndex) ?: ""
          val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L

          if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
            subdirs.add(childId to displayName)
          } else if (mimeType.startsWith("audio/") || displayName.matches(Regex(".*\\.(mp3|flac|wav|m4a|aac|ogg|wma|opus|3gp|mp4)$", RegexOption.IGNORE_CASE))) {
            if (jsonArray.length() + audioItems.size < 2000) {
              audioItems.add(Triple(childId, displayName, size))
            }
          } else if (mimeType.startsWith("image/") || displayName.matches(Regex(".*\\.(jpg|jpeg|png|webp|bmp|gif|jfif|heic)$", RegexOption.IGNORE_CASE))) {
            imageItems.add(childId to displayName)
          }
        }

        // 1. Localiza a melhor capa solta dentro da própria pasta (ex: cover.jpg, folder.jpg, capa.png, ou qualquer imagem da pasta)
        var folderCoverUrl: String? = null
        if (imageItems.isNotEmpty()) {
          val bestImage = imageItems.find { (_, name) ->
            val n = name.lowercase()
            n.contains("cover") || n.contains("folder") || n.contains("album") ||
                n.contains("front") || n.contains("capa") || n.contains("art") || n.contains("thumb")
          } ?: imageItems.first()

          val coverDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, bestImage.first)
          folderCoverUrl = "https://soundwave.local/cover?uri=" + java.net.URLEncoder.encode(coverDocUri.toString(), "UTF-8")
        }

        // 2. Se não houver imagem solta na pasta atual, verifica subpastas de capa (Artwork/Covers) ou herda da pasta pai (ex: CD1/CD2)
        if (folderCoverUrl == null && audioItems.isNotEmpty() && subdirs.isNotEmpty()) {
          folderCoverUrl = findFirstCoverInSubdirs(treeUri, subdirs)
        }
        if (folderCoverUrl == null) {
          folderCoverUrl = inheritedCoverUrl
        }

        val rawFolderName = if (currentPath.contains("/")) currentPath.substringAfterLast("/").trim() else (if (currentPath.isNotEmpty()) currentPath.trim() else "Pasta Selecionada")
        val parentFolder = rawFolderName.ifEmpty { "Pasta Selecionada" }

        var artistName = "Arquivo Local"
        var albumName = parentFolder
        if (parentFolder.contains(" - ")) {
          artistName = parentFolder.substringBefore(" - ").trim()
          albumName = parentFolder.substringAfter(" - ").trim()
        } else if (currentPath.contains(" / ")) {
          val parts = currentPath.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
          if (parts.size >= 2) {
            artistName = parts[parts.size - 2]
            albumName = parts.last()
          }
        }

        for ((childId, displayName, size) in audioItems) {
          val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
          val trackTitle = displayName.replace(Regex("\\.[^/.]+$"), "")
          // Tenta usar imagem específica com mesmo nome da faixa se existir, senão usa a capa da pasta
          val matchingTrackImage = imageItems.find { (_, imgName) ->
            imgName.replace(Regex("\\.[^/.]+$"), "").equals(trackTitle, ignoreCase = true)
          }
          val specificTrackCoverUrl = if (matchingTrackImage != null) {
            val trkImgUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, matchingTrackImage.first)
            "https://soundwave.local/cover?uri=" + java.net.URLEncoder.encode(trkImgUri.toString(), "UTF-8")
          } else null

          val audioCoverFallback = "https://soundwave.local/audio_cover?uri=" + java.net.URLEncoder.encode(docUri.toString(), "UTF-8")

          val obj = org.json.JSONObject().apply {
            put("id", Math.abs(docUri.toString().hashCode()))
            put("name", displayName)
            put("title", trackTitle)
            put("artist", artistName)
            put("album", albumName)
            put("parentFolder", parentFolder)
            put("relPath", if (currentPath.isEmpty()) displayName else "$currentPath / $displayName")
            put("uri", docUri.toString())
            put("coverUrl", specificTrackCoverUrl ?: folderCoverUrl ?: audioCoverFallback)
            put("hasFolderCover", (specificTrackCoverUrl != null || folderCoverUrl != null))
            put("size", size)
          }
          jsonArray.put(obj)
        }

        for ((subId, subName) in subdirs) {
          val nextPath = if (currentPath.isEmpty()) subName else "$currentPath / $subName"
          scanDirRecursive(treeUri, subId, nextPath, jsonArray, folderCoverUrl)
        }
      }
    } catch (e: Exception) {
      Log.w("SoundWaveFolderScanner", "Error querying children for $parentDocId: ${e.message}")
    }
  }

  private fun monitorDownloadProgress(downloadId: Long, fileName: String, manager: DownloadManager) {
    lifecycleScope.launch(Dispatchers.IO) {
      var isDownloading = true
      var lastReportedPercent = -1

      while (isDownloading && isActive) {
        try {
          val query = DownloadManager.Query().setFilterById(downloadId)
          val cursor = manager.query(query)
          if (cursor != null && cursor.moveToFirst()) {
            val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

            val bytesDownloaded = if (bytesCol != -1) cursor.getLong(bytesCol) else 0L
            val totalBytes = if (totalCol != -1) cursor.getLong(totalCol) else 0L
            val status = if (statusCol != -1) cursor.getInt(statusCol) else -1
            val reason = if (reasonCol != -1) cursor.getInt(reasonCol) else 0

            cursor.close()

            when (status) {
              DownloadManager.STATUS_RUNNING -> {
                if (totalBytes > 0) {
                  val percent = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                  if (percent != lastReportedPercent && (percent - lastReportedPercent >= 10 || percent == 100)) {
                    lastReportedPercent = percent
                    withContext(Dispatchers.Main) {
                      val qName = JSONObject.quote(fileName)
                      webView?.evaluateJavascript(
                          "if (window.onNativeDownloadProgress) { window.onNativeDownloadProgress($downloadId, $qName, $percent); }",
                          null
                      )
                    }
                  }
                }
              }
              DownloadManager.STATUS_SUCCESSFUL -> {
                isDownloading = false
                withContext(Dispatchers.Main) {
                  val qName = JSONObject.quote(fileName)
                  Toast.makeText(this@MainActivity, "✅ Download concluído: $fileName (em Download/SoundWave)", Toast.LENGTH_SHORT).show()
                  webView?.evaluateJavascript(
                      "if (window.onNativeDownloadComplete) { window.onNativeDownloadComplete($downloadId, $qName); }",
                      null
                  )
                }
              }
              DownloadManager.STATUS_FAILED -> {
                isDownloading = false
                withContext(Dispatchers.Main) {
                  val qName = JSONObject.quote(fileName)
                  val errMsg = "Falha no download (código $reason)"
                  val qErr = JSONObject.quote(errMsg)
                  Toast.makeText(this@MainActivity, "❌ $errMsg: $fileName", Toast.LENGTH_LONG).show()
                  webView?.evaluateJavascript(
                      "if (window.onNativeDownloadError) { window.onNativeDownloadError($downloadId, $qName, $qErr); }",
                      null
                  )
                }
              }
            }
          } else {
            cursor?.close()
            isDownloading = false
          }
        } catch (e: Exception) {
          Log.w("SoundWaveDownload", "Error querying download progress: ${e.message}")
          isDownloading = false
        }
        delay(1200)
      }
    }
  }

  inner class AndroidTorrentBridge {

    @JavascriptInterface
    fun scanDeviceMediaStore() {
      runOnUiThread {
        requestStoragePermissionsAndScan(false)
      }
    }

    @JavascriptInterface
    fun scanDeviceFolder() {
      runOnUiThread {
        try {
          val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
          }
          folderChooserLauncher.launch(intent)
        } catch (e: Exception) {
          Toast.makeText(this@MainActivity, "Iniciando varredura nativa do armazenamento...", Toast.LENGTH_SHORT).show()
          requestStoragePermissionsAndScan(false)
        }
      }
    }

    @JavascriptInterface
    fun pickAudioFiles() {
      runOnUiThread {
        try {
          val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "audio/*",
                    "application/ogg",
                    "application/x-ogg",
                    "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
          }
          audioFilePickerLauncher.launch(intent)
        } catch (e: Exception) {
          try {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
              type = "audio/*"
              putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
              addCategory(Intent.CATEGORY_OPENABLE)
            }
            audioFilePickerLauncher.launch(fallback)
          } catch (e2: Exception) {
            Toast.makeText(this@MainActivity, "Erro ao abrir seletor: ${e2.message}", Toast.LENGTH_SHORT).show()
          }
        }
      }
    }

    @JavascriptInterface
    fun syncLastFolder(): Boolean {
      val prefs = getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
      val savedUriStr = prefs.getString("last_folder_uri", null)
      if (savedUriStr.isNullOrEmpty()) {
        runOnUiThread {
          requestStoragePermissionsAndScan(true)
        }
        return true
      }

      val treeUri = Uri.parse(savedUriStr)
      lifecycleScope.launch(Dispatchers.IO) {
        val jsonArray = scanFolderTree(treeUri)
        if (jsonArray.length() == 0) {
          // Fallback to MediaStore scan if SAF tree returns empty
          scanDeviceMediaStore(true)
        } else {
          withContext(Dispatchers.Main) {
            val quoted = JSONObject.quote(jsonArray.toString())
            webView?.evaluateJavascript(
                "if (window.onNativeFolderScanned) { window.onNativeFolderScanned($quoted, true); }",
                null)
          }
        }
      }
      return true
    }

    @JavascriptInterface
    fun getLastFolderUri(): String {
      val prefs = getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
      val saved = prefs.getString("last_folder_uri", "") ?: ""
      if (saved.isEmpty()) return "Memória Interna"
      return try {
        val decoded = Uri.decode(saved)
        if (decoded.contains(":")) decoded.substringAfterLast(":") else decoded
      } catch (_: Exception) {
        "Memória Interna"
      }
    }

    /**
     * Non-blocking asynchronous search. Fetches data on Dispatchers.IO and posts back to JavaScript
     * via evaluateJavascript to avoid freezing the Chromium renderer/IPC thread.
     */
    @JavascriptInterface
    fun searchRealTorrentsAsync(query: String, callbackId: String) {
      lifecycleScope.launch(Dispatchers.IO) {
        val result =
            try {
              val encoded = URLEncoder.encode(query.trim(), "UTF-8")
              val url = "https://apibay.org/q.php?q=$encoded&cat=100"
              val request =
                  Request.Builder()
                      .url(url)
                      .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/1.0")
                      .header("Accept", "application/json")
                      .build()
              val response = okHttpClient.newCall(request).execute()
              response.body?.string() ?: "[]"
            } catch (e: Exception) {
              Log.w("SoundWaveBridge", "Async search error: ${e.message}")
              "[]"
            }

        withContext(Dispatchers.Main) {
          val quoted = JSONObject.quote(result)
          webView?.evaluateJavascript(
              "if (window.onRealTorrentsLoaded) { window.onRealTorrentsLoaded('$callbackId', $quoted); }",
              null)
        }
      }
    }

    @JavascriptInterface
    fun searchCoverArtAsync(query: String, fallbackQuery: String, callbackId: String) {
      lifecycleScope.launch(Dispatchers.IO) {
        var foundCoverUrl: String? = null
        val queriesToTry = listOf(query.trim(), fallbackQuery.trim()).filter { it.length >= 2 }.distinct()

        for (q in queriesToTry) {
          if (!foundCoverUrl.isNullOrEmpty()) break
          val encoded = try { URLEncoder.encode(q, "UTF-8") } catch (_: Exception) { continue }

          // 1. Deezer Search API (Track / Album cover)
          try {
            val req = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=3")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/1.0")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
              if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val dataArr = json.optJSONArray("data")
                if (dataArr != null && dataArr.length() > 0) {
                  for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val albumObj = item.optJSONObject("album")
                    val coverBig = albumObj?.optString("cover_xl")?.takeIf { it.startsWith("http") }
                        ?: albumObj?.optString("cover_big")?.takeIf { it.startsWith("http") }
                        ?: albumObj?.optString("cover_medium")?.takeIf { it.startsWith("http") }
                    if (!coverBig.isNullOrEmpty()) {
                      foundCoverUrl = coverBig
                      break
                    }
                    val artistObj = item.optJSONObject("artist")
                    val picBig = artistObj?.optString("picture_xl")?.takeIf { it.startsWith("http") }
                        ?: artistObj?.optString("picture_big")?.takeIf { it.startsWith("http") }
                    if (!picBig.isNullOrEmpty()) {
                      foundCoverUrl = picBig
                      break
                    }
                  }
                }
              }
            }
          } catch (_: Exception) {}

          if (!foundCoverUrl.isNullOrEmpty()) break

          // 2. iTunes Search API (Album / Song artwork)
          try {
            val req = Request.Builder()
                .url("https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=3&country=BR")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/1.0")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
              if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val resultsArr = json.optJSONArray("results")
                if (resultsArr != null && resultsArr.length() > 0) {
                  val art100 = resultsArr.optJSONObject(0)?.optString("artworkUrl100") ?: ""
                  if (art100.startsWith("http")) {
                    foundCoverUrl = art100.replace("100x100bb", "600x600bb")
                  }
                }
              }
            }
          } catch (_: Exception) {}

          if (!foundCoverUrl.isNullOrEmpty()) break

          // 3. iTunes Search API (Album entity specifically)
          try {
            val req = Request.Builder()
                .url("https://itunes.apple.com/search?term=$encoded&media=music&entity=album&limit=3&country=BR")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/1.0")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
              if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val resultsArr = json.optJSONArray("results")
                if (resultsArr != null && resultsArr.length() > 0) {
                  val art100 = resultsArr.optJSONObject(0)?.optString("artworkUrl100") ?: ""
                  if (art100.startsWith("http")) {
                    foundCoverUrl = art100.replace("100x100bb", "600x600bb")
                  }
                }
              }
            }
          } catch (_: Exception) {}
        }

        withContext(Dispatchers.Main) {
          val quotedUrl = if (foundCoverUrl != null) JSONObject.quote(foundCoverUrl) else "null"
          val quotedCb = JSONObject.quote(callbackId)
          webView?.evaluateJavascript(
              "if (window.onNativeCoverLoaded) { window.onNativeCoverLoaded($quotedCb, $quotedUrl); }",
              null)
        }
      }
    }

    @JavascriptInterface
    fun fetchUrlNativeAsync(url: String, callbackId: String) {
      lifecycleScope.launch(Dispatchers.IO) {
        val bodyStr =
            try {
              if (!url.startsWith("https://") && !url.startsWith("http://")) {
                ""
              } else {
                val req =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                        .header("Accept", "application/json, text/plain, */*")
                        .build()
                okHttpClient.newCall(req).execute().use { resp ->
                  if (resp.isSuccessful) {
                    resp.body?.string() ?: ""
                  } else {
                    ""
                  }
                }
              }
            } catch (e: Exception) {
              Log.w("SoundWaveBridge", "fetchUrlNativeAsync error ($url): ${e.message}")
              ""
            }

        withContext(Dispatchers.Main) {
          val quotedBody = JSONObject.quote(bodyStr)
          val quotedCb = JSONObject.quote(callbackId)
          webView?.evaluateJavascript(
              "if (window.onNativeUrlFetched) { window.onNativeUrlFetched($quotedCb, $quotedBody); }",
              null)
        }
      }
    }

    private fun parseIso8601DurationToSeconds(iso: String?): Int {
      if (iso.isNullOrEmpty()) return 0
      val regex = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?", RegexOption.IGNORE_CASE)
      val match = regex.find(iso) ?: return 0
      val hours = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
      val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
      val seconds = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
      return (hours * 3600) + (minutes * 60) + seconds
    }

    private fun parseHumanDurationToSeconds(text: String?): Int {
      if (text.isNullOrEmpty()) return 0
      val parts = text.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
      return when (parts.size) {
        2 -> (parts[0] * 60) + parts[1]
        3 -> (parts[0] * 3600) + (parts[1] * 60) + parts[2]
        else -> 0
      }
    }

    private fun isOfficialMusicChannelOrRelease(channelName: String, title: String, isVerifiedBadge: Boolean): Boolean {
      val c = channelName.lowercase()
      val t = title.lowercase()

      // Descartar pessoas comuns, amadores, reacts, tutoriais e ruídos
      val amateurKeywords = listOf(
          "react", "reagindo", "como tocar", "aula de", "aula violao", "aula guitarra",
          "karaoke", "playback", "status whatsapp", "status para whatsapp", "tipografia",
          "slowed", "reverb", "bass boosted", "8d audio", "gameplay", "vlog",
          "cover amador", "ao vivo no churrasco", "parodia", "churrasco", "trolagem",
          "unboxing", "review", "resenha", "tutorial", "podcast", "corte de podcast", "cortes"
      )
      if (amateurKeywords.any { t.contains(it) || c.contains(it) }) {
        return false
      }

      if (isVerifiedBadge) return true

      // Padrões de canais oficiais e gravadoras
      val officialChannelPatterns = listOf(
          " - topic", "vevo", "oficial", "official", "gravadora", "records", "music",
          "som livre", "warner music", "universal music", "sony music", "kondzilla",
          "gr6 explode", "workshow", "audiomix", "love funk", "legenda funk",
          "deckdisc", "mk music", "todah music", "bizzu", "sua musica", "radar records",
          "spinnin", "ultra records", "sertanejo", "pagode", "funk"
      )
      if (officialChannelPatterns.any { c.contains(it) }) return true

      // Padrões de títulos oficiais de lançamentos
      val officialTitlePatterns = listOf(
          "clipe oficial", "vídeo oficial", "video oficial", "áudio oficial", "audio oficial",
          "official music video", "official video", "official audio", "visualizer oficial",
          "lyric video oficial", "ao vivo oficial", "dvd oficial", "single oficial",
          "oficial video", "official lyric video"
      )
      if (officialTitlePatterns.any { t.contains(it) }) return true

      return false
    }

    private fun extractVideoRenderersFromJson(
        node: Any?,
        outList: MutableList<JSONObject>,
        seenIds: MutableSet<String>,
        onlyOfficial: Boolean = false,
        depth: Int = 0
    ) {
      if (node == null || outList.size >= 24 || depth > 20) return
      when (node) {
        is JSONObject -> {
          if (node.has("videoRenderer")) {
            val vr = node.optJSONObject("videoRenderer")
            val videoId = vr?.optString("videoId") ?: ""
            if (videoId.length == 11 && !seenIds.contains(videoId)) {
              val titleRuns = vr?.optJSONObject("title")?.optJSONArray("runs")
              val rawTitle =
                  if (titleRuns != null && titleRuns.length() > 0) {
                    titleRuns.optJSONObject(0)?.optString("text") ?: ""
                  } else {
                    vr?.optJSONObject("title")?.optString("simpleText") ?: ""
                  }
              if (rawTitle.isNotEmpty()) {
                val ownerRuns =
                    vr?.optJSONObject("ownerText")?.optJSONArray("runs")
                        ?: vr?.optJSONObject("longBylineText")?.optJSONArray("runs")
                        ?: vr?.optJSONObject("shortBylineText")?.optJSONArray("runs")
                val channelName =
                    if (ownerRuns != null && ownerRuns.length() > 0) {
                      ownerRuns.optJSONObject(0)?.optString("text") ?: "Canal YouTube"
                    } else {
                      "Canal YouTube"
                    }

                val ownerBadges = vr?.optJSONArray("ownerBadges")
                var isVerified = false
                if (ownerBadges != null) {
                  for (b in 0 until ownerBadges.length()) {
                    val bObj = ownerBadges.optJSONObject(b)?.optJSONObject("metadataBadgeRenderer")
                    val style = bObj?.optString("style") ?: ""
                    val tooltip = bObj?.optString("tooltip") ?: ""
                    if (style.contains("VERIFIED") || style.contains("ARTIST") ||
                        tooltip.contains("Oficial", ignoreCase = true) ||
                        tooltip.contains("Official", ignoreCase = true) ||
                        tooltip.contains("Verificado", ignoreCase = true)) {
                      isVerified = true
                      break
                    }
                  }
                }

                val isOfficial = isOfficialMusicChannelOrRelease(channelName, rawTitle, isVerified)
                if (!onlyOfficial || isOfficial) {
                  val lengthStr =
                      vr?.optJSONObject("lengthText")?.optString("simpleText")
                          ?: vr?.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                          ?: ""
                  val durSecs = parseHumanDurationToSeconds(lengthStr)
                  val thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                  val publishedTimeStr =
                      vr?.optJSONObject("publishedTimeText")?.optString("simpleText")
                          ?: vr?.optJSONObject("publishedTimeText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                          ?: ""
                  val pubLower = publishedTimeStr.lowercase()
                  val isUnder24h = pubLower.isNotEmpty() &&
                      (pubLower.contains("hora") || pubLower.contains("hour") ||
                          pubLower.contains("minuto") || pubLower.contains("minute") ||
                          pubLower.contains("segundo") || pubLower.contains("second") ||
                          pubLower.contains("agora") || pubLower.contains("just now")) &&
                      !pubLower.contains("dia") && !pubLower.contains("day") &&
                      !pubLower.contains("semana") && !pubLower.contains("week") &&
                      !pubLower.contains("mês") && !pubLower.contains("mes") && !pubLower.contains("month") &&
                      !pubLower.contains("ano") && !pubLower.contains("year")

                  val numMatch = Regex("(\\d+)").find(pubLower)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1L
                  val approxAgeMs = when {
                    pubLower.contains("segundo") || pubLower.contains("second") -> numMatch * 1000L
                    pubLower.contains("minuto") || pubLower.contains("minute") -> numMatch * 60_000L
                    pubLower.contains("hora") || pubLower.contains("hour") -> numMatch * 3_600_000L
                    pubLower.contains("dia") || pubLower.contains("day") -> numMatch * 86_400_000L
                    else -> if (isUnder24h) 3_600_000L else 3 * 86_400_000L
                  }
                  val approxIso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - approxAgeMs).toString()

                  seenIds.add(videoId)
                  outList.add(
                      JSONObject().apply {
                        put("videoId", videoId)
                        put("title", rawTitle)
                        put("channelTitle", channelName)
                        put("thumbnailUrl", thumbUrl)
                        put("duration", durSecs)
                        put("durationText", lengthStr)
                        put("publishedTimeText", publishedTimeStr)
                        put("publishedAt", approxIso)
                        put("isNew24h", isUnder24h)
                        put("isOfficial", isOfficial)
                        put("isVerified", isVerified)
                        put("source", if (isOfficial) "YouTube Oficial" else "YouTube")
                      })
                }
              }
            }
          }
          val keys = node.keys()
          while (keys.hasNext() && outList.size < 24) {
            val k = keys.next()
            extractVideoRenderersFromJson(node.opt(k), outList, seenIds, onlyOfficial, depth + 1)
          }
        }
        is org.json.JSONArray -> {
          for (i in 0 until node.length()) {
            if (outList.size >= 24) break
            extractVideoRenderersFromJson(node.opt(i), outList, seenIds, onlyOfficial, depth + 1)
          }
        }
      }
    }

    @JavascriptInterface
    fun searchYouTubeVideosAsync(query: String, callbackId: String) {
      lifecycleScope.launch(Dispatchers.IO) {
        val resultsArray = org.json.JSONArray()
        val seenVideoIds = mutableSetOf<String>()
        val cleanQuery = query.trim()

        if (cleanQuery.length >= 2) {
          val encoded = try { URLEncoder.encode(cleanQuery, "UTF-8") } catch (_: Exception) { "" }
          val apiKey = try { BuildConfig.YOUTUBE_API_KEY.trim() } catch (_: Exception) { "" }

          // 1. Primary: Official YouTube Data API v3 (when YOUTUBE_API_KEY is configured in AI Studio Secrets)
          if (encoded.isNotEmpty() &&
              apiKey.isNotEmpty() &&
              apiKey != "MY_YOUTUBE_API_KEY" &&
              !apiKey.startsWith("YOUR_")) {
            try {
              val searchUrl =
                  "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&order=relevance&maxResults=22&q=$encoded&key=$apiKey"
              val req =
                  Request.Builder()
                      .url(searchUrl)
                      .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                      .header("Accept", "application/json")
                      .build()

              val tempItems = mutableListOf<JSONObject>()
              val idList = mutableListOf<String>()

              okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                  val bodyStr = resp.body?.string() ?: ""
                  val json = JSONObject(bodyStr)
                  val items = json.optJSONArray("items")
                  if (items != null) {
                    for (i in 0 until items.length()) {
                      val item = items.optJSONObject(i) ?: continue
                      val idObj = item.optJSONObject("id")
                      val videoId = idObj?.optString("videoId") ?: ""
                      val snippet = item.optJSONObject("snippet") ?: continue
                      if (videoId.isNotEmpty() && !seenVideoIds.contains(videoId)) {
                        seenVideoIds.add(videoId)
                        idList.add(videoId)
                        val title =
                            android.text.Html.fromHtml(
                                    snippet.optString("title", "Vídeo Musical"),
                                    android.text.Html.FROM_HTML_MODE_LEGACY)
                                .toString()
                        val channelTitle = snippet.optString("channelTitle", "YouTube")
                        val thumbs = snippet.optJSONObject("thumbnails")
                        val thumbUrl =
                            thumbs?.optJSONObject("high")?.optString("url")
                                ?: thumbs?.optJSONObject("medium")?.optString("url")
                                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                        tempItems.add(
                            JSONObject().apply {
                              put("videoId", videoId)
                              put("title", title)
                              put("channelTitle", channelTitle)
                              put("thumbnailUrl", thumbUrl)
                              put("duration", 0)
                              put("source", "YouTube Data API v3")
                            })
                      }
                    }
                  }
                }
              }

              // Fetch exact video durations from YouTube Data API v3 videos.list endpoint
              if (idList.isNotEmpty()) {
                val durationMap = mutableMapOf<String, Int>()
                try {
                  val idsCsv = URLEncoder.encode(idList.joinToString(","), "UTF-8")
                  val detailsUrl =
                      "https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=$idsCsv&key=$apiKey"
                  val detailsReq =
                      Request.Builder()
                          .url(detailsUrl)
                          .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                          .build()
                  okHttpClient.newCall(detailsReq).execute().use { dResp ->
                    if (dResp.isSuccessful) {
                      val dJson = JSONObject(dResp.body?.string() ?: "{}")
                      val dItems = dJson.optJSONArray("items")
                      if (dItems != null) {
                        for (j in 0 until dItems.length()) {
                          val dObj = dItems.optJSONObject(j) ?: continue
                          val vId = dObj.optString("id")
                          val isoDur =
                              dObj.optJSONObject("contentDetails")?.optString("duration")
                          val secs = parseIso8601DurationToSeconds(isoDur)
                          if (vId.isNotEmpty() && secs > 0) {
                            durationMap[vId] = secs
                          }
                        }
                      }
                    }
                  }
                } catch (e: Exception) {
                  Log.w("SoundWaveYouTube", "Error fetching video durations: ${e.message}")
                }

                for (obj in tempItems) {
                  val vId = obj.optString("videoId")
                  if (durationMap.containsKey(vId)) {
                    obj.put("duration", durationMap[vId] ?: 0)
                  }
                  resultsArray.put(obj)
                }
              }
            } catch (e: Exception) {
              Log.w("SoundWaveYouTube", "YouTube Data API v3 search error: ${e.message}")
            }
          }

          // 2. Complementary Discovery Fallback (when YOUTUBE_API_KEY is not yet configured or quota exceeded)
          if (resultsArray.length() == 0 && encoded.isNotEmpty()) {
            try {
              val ytSearchUrl = "https://www.youtube.com/results?search_query=$encoded&sp=EgIQAQ%253D%253D"
              val req =
                  Request.Builder()
                      .url(ytSearchUrl)
                      .header(
                          "User-Agent",
                          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                      .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8")
                      .build()
              okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                  val html = resp.body?.string() ?: ""
                  val marker = "var ytInitialData = "
                  val startIdx = html.indexOf(marker)
                  if (startIdx != -1) {
                    val jsonStart = startIdx + marker.length
                    val endIdx = html.indexOf(";</script>", jsonStart)
                    if (endIdx != -1) {
                      val rawJson = html.substring(jsonStart, endIdx).trim()
                      val rootObj = JSONObject(rawJson)
                      val extracted = mutableListOf<JSONObject>()
                      extractVideoRenderersFromJson(rootObj, extracted, seenVideoIds)
                      for (item in extracted) {
                        resultsArray.put(item)
                      }
                    }
                  }
                }
              }
            } catch (e: Exception) {
              Log.w("SoundWaveYouTube", "YouTube fallback discovery error: ${e.message}")
            }
          }
        }

        val jsonOut = resultsArray.toString()
        withContext(Dispatchers.Main) {
          val quotedBody = JSONObject.quote(jsonOut)
          val quotedCb = JSONObject.quote(callbackId)
          webView?.evaluateJavascript(
              "if (window.onYouTubeSearchLoaded) { window.onYouTubeSearchLoaded($quotedCb, $quotedBody); }",
              null)
        }
      }
    }

    @JavascriptInterface
    fun fetchYouTubeDiscoverAsync(categoryQuery: String, publishedAfterIso: String, callbackId: String) {
      lifecycleScope.launch(Dispatchers.IO) {
        val resultsArray = org.json.JSONArray()
        val seenVideoIds = mutableSetOf<String>()
        val cleanQuery = categoryQuery.trim().ifEmpty { "musica lancamento brasil" }
        val officialQuery = if (!cleanQuery.contains("oficial", ignoreCase = true) && !cleanQuery.contains("vevo", ignoreCase = true)) {
          "\"Vídeo Oficial\" $cleanQuery"
        } else {
          cleanQuery
        }
        val encodedQuery = try { URLEncoder.encode(officialQuery, "UTF-8") } catch (_: Exception) { "" }
        val encodedPub = try { URLEncoder.encode(publishedAfterIso.trim(), "UTF-8") } catch (_: Exception) { "" }
        val apiKey = try { BuildConfig.YOUTUBE_API_KEY.trim() } catch (_: Exception) { "" }

        val isInternationalQuery =
            cleanQuery.contains("Official Music Video", ignoreCase = true) &&
                cleanQuery.contains("-clipe", ignoreCase = true)
        val regionParam = if (isInternationalQuery) "regionCode=US&relevanceLanguage=en" else "regionCode=BR&relevanceLanguage=pt"
        val acceptLangHeader = if (isInternationalQuery) "en-US,en;q=0.9" else "pt-BR,pt;q=0.9,en-US;q=0.8"

        if (encodedQuery.isNotEmpty() &&
            apiKey.isNotEmpty() &&
            apiKey != "MY_YOUTUBE_API_KEY" &&
            !apiKey.startsWith("YOUR_")) {
          try {
            val pubParam = if (encodedPub.isNotEmpty()) "&publishedAfter=$encodedPub" else ""
            val searchUrl =
                "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoCategoryId=10&videoEmbeddable=true&$regionParam&order=relevance&maxResults=25&q=$encodedQuery$pubParam&key=$apiKey"
            val req =
                Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                    .header("Accept", "application/json")
                    .build()

            val tempItems = mutableListOf<JSONObject>()
            val idList = mutableListOf<String>()

            okHttpClient.newCall(req).execute().use { resp ->
              if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val items = json.optJSONArray("items")
                if (items != null) {
                  for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val idObj = item.optJSONObject("id")
                    val videoId = idObj?.optString("videoId") ?: ""
                    val snippet = item.optJSONObject("snippet") ?: continue
                    if (videoId.isNotEmpty() && !seenVideoIds.contains(videoId)) {
                      val title =
                          android.text.Html.fromHtml(
                                  snippet.optString("title", "Vídeo Musical"),
                                  android.text.Html.FROM_HTML_MODE_LEGACY)
                              .toString()
                      val channelTitle = snippet.optString("channelTitle", "YouTube")
                      
                      // Filtrar estritamente canais oficiais / gravadoras / lançamentos legítimos
                      val isOfficial = isOfficialMusicChannelOrRelease(channelTitle, title, false)
                      if (!isOfficial) continue

                      seenVideoIds.add(videoId)
                      idList.add(videoId)
                      val publishedAt = snippet.optString("publishedAt", "")
                      val isNew24h = try {
                        if (publishedAt.isNotEmpty()) {
                          val pubMillis = java.time.Instant.parse(publishedAt).toEpochMilli()
                          val diff = System.currentTimeMillis() - pubMillis
                          diff in -3_600_000L..(24 * 3_600_000L)
                        } else false
                      } catch (_: Exception) {
                        false
                      }
                      val thumbs = snippet.optJSONObject("thumbnails")
                      val thumbUrl =
                          thumbs?.optJSONObject("high")?.optString("url")
                              ?: thumbs?.optJSONObject("medium")?.optString("url")
                              ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                      tempItems.add(
                          JSONObject().apply {
                            put("videoId", videoId)
                            put("title", title)
                            put("channelTitle", channelTitle)
                            put("thumbnailUrl", thumbUrl)
                            put("publishedAt", publishedAt)
                            put("isNew24h", isNew24h)
                            put("duration", 0)
                            put("isOfficial", true)
                            put("source", "YouTube Oficial")
                          })
                    }
                  }
                }
              }
            }

            if (idList.isNotEmpty()) {
              val durationMap = mutableMapOf<String, Int>()
              try {
                val idsCsv = URLEncoder.encode(idList.joinToString(","), "UTF-8")
                val detailsUrl =
                    "https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=$idsCsv&key=$apiKey"
                val detailsReq =
                    Request.Builder()
                        .url(detailsUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                        .build()
                okHttpClient.newCall(detailsReq).execute().use { dResp ->
                  if (dResp.isSuccessful) {
                    val dJson = JSONObject(dResp.body?.string() ?: "{}")
                    val dItems = dJson.optJSONArray("items")
                    if (dItems != null) {
                      for (j in 0 until dItems.length()) {
                        val dObj = dItems.optJSONObject(j) ?: continue
                        val vId = dObj.optString("id")
                        val isoDur = dObj.optJSONObject("contentDetails")?.optString("duration")
                        val secs = parseIso8601DurationToSeconds(isoDur)
                        if (vId.isNotEmpty() && secs > 0) {
                          durationMap[vId] = secs
                        }
                      }
                    }
                  }
                }
              } catch (e: Exception) {
                Log.w("SoundWaveDiscover", "Error fetching video durations: ${e.message}")
              }

              for (obj in tempItems) {
                val vId = obj.optString("videoId")
                if (durationMap.containsKey(vId)) {
                  obj.put("duration", durationMap[vId] ?: 0)
                }
                resultsArray.put(obj)
              }
            }
          } catch (e: Exception) {
            Log.w("SoundWaveDiscover", "YouTube Data API v3 discover error: ${e.message}")
          }
        }

        // Fallback recente: busca primeiro lançamentos das últimas 24h (sp=EgIIAg%253D%253D) + esta semana (sp=CAISAhAB)
        if (resultsArray.length() == 0 && encodedQuery.isNotEmpty()) {
          val spFilters = listOf("EgIIAg%253D%253D", "CAISAhAB")
          for (spFilter in spFilters) {
            if (resultsArray.length() >= 20) break
            try {
              val ytSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=$spFilter"
              val req =
                  Request.Builder()
                      .url(ytSearchUrl)
                      .header(
                          "User-Agent",
                          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                      .header("Accept-Language", acceptLangHeader)
                      .build()
              okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                  val html = resp.body?.string() ?: ""
                  val marker = "var ytInitialData = "
                  val startIdx = html.indexOf(marker)
                  if (startIdx != -1) {
                    val jsonStart = startIdx + marker.length
                    val endIdx = html.indexOf(";</script>", jsonStart)
                    if (endIdx != -1) {
                      val rawJson = html.substring(jsonStart, endIdx).trim()
                      val rootObj = JSONObject(rawJson)
                      val extracted = mutableListOf<JSONObject>()
                      extractVideoRenderersFromJson(rootObj, extracted, seenVideoIds, onlyOfficial = true)
                      for (item in extracted) {
                        if (spFilter == "EgIIAg%253D%253D") {
                          item.put("isNew24h", true)
                        }
                        resultsArray.put(item)
                      }
                    }
                  }
                }
              }
            } catch (e: Exception) {
              Log.w("SoundWaveDiscover", "YouTube discover fallback error ($spFilter): ${e.message}")
            }
          }
        }

        val jsonOut = resultsArray.toString()
        withContext(Dispatchers.Main) {
          val quotedBody = JSONObject.quote(jsonOut)
          val quotedCb = JSONObject.quote(callbackId)
          webView?.evaluateJavascript(
              "if (window.onYouTubeDiscoverLoaded) { window.onYouTubeDiscoverLoaded($quotedCb, $quotedBody); }",
              null)
        }
      }
    }

    @JavascriptInterface
    fun fetchOfficialChartsAndReleasesAsync(genreKey: String, callbackId: String) {
      lifecycleScope.launch(Dispatchers.IO) {
        val resultsArray = org.json.JSONArray()
        val seenKeys = mutableSetOf<String>()

        try {
          val rssUrl = "https://rss.applemarketingtools.com/api/v2/br/music/most-played/50/songs.json"
          val req = Request.Builder()
              .url(rssUrl)
              .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
              .build()

          okHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
              val body = resp.body?.string() ?: ""
              val json = JSONObject(body)
              val feed = json.optJSONObject("feed")
              val results = feed?.optJSONArray("results")
              if (results != null) {
                for (i in 0 until results.length()) {
                  val item = results.optJSONObject(i) ?: continue
                  val id = item.optString("id")
                  val name = item.optString("name")
                  val artist = item.optString("artistName")
                  val releaseDate = item.optString("releaseDate")
                  val rawArt = item.optString("artworkUrl100")
                  val coverArt = if (rawArt.isNotEmpty()) rawArt.replace("100x100bb", "600x600bb") else ""
                  val genresArr = item.optJSONArray("genres")
                  val genreName = genresArr?.optJSONObject(0)?.optString("name") ?: "Música"

                  val dedup = "${artist.lowercase()}_${name.lowercase()}"
                  if (name.isNotEmpty() && !seenKeys.contains(dedup)) {
                    seenKeys.add(dedup)
                    resultsArray.put(JSONObject().apply {
                      put("id", "it_$id")
                      put("title", name)
                      put("artist", artist)
                      put("album", "Lançamentos & Top Brasil")
                      put("releaseDate", releaseDate)
                      put("coverUrl", coverArt)
                      put("genre", genreName)
                      put("source", "Apple Music Brasil")
                      put("rank", i + 1)
                    })
                  }
                }
              }
            }
          }
        } catch (e: Exception) {
          Log.w("SoundWaveDiscover", "Error fetching Apple RSS: ${e.message}")
        }

        if (resultsArray.length() < 12) {
          try {
            val q = if (genreKey.isNotEmpty() && genreKey != "brasil" && genreKey != "all") {
              "lancamentos $genreKey"
            } else {
              "lancamentos brasil 2026"
            }
            val enc = URLEncoder.encode(q, "UTF-8")
            val itunesUrl = "https://itunes.apple.com/search?term=$enc&entity=song&limit=30&country=BR"
            val req = Request.Builder().url(itunesUrl).build()
            okHttpClient.newCall(req).execute().use { resp ->
              if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                val res = json.optJSONArray("results")
                if (res != null) {
                  for (i in 0 until res.length()) {
                    val item = res.optJSONObject(i) ?: continue
                    val tName = item.optString("trackName")
                    val aName = item.optString("artistName")
                    val cName = item.optString("collectionName")
                    val rawDate = item.optString("releaseDate")
                    val prevUrl = item.optString("previewUrl")
                    val art = item.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                    val durSecs = (item.optLong("trackTimeMillis", 0) / 1000).toInt()

                    val dedup = "${aName.lowercase()}_${tName.lowercase()}"
                    if (tName.isNotEmpty() && !seenKeys.contains(dedup)) {
                      seenKeys.add(dedup)
                      resultsArray.put(JSONObject().apply {
                        put("id", "it_${item.optString("trackId")}")
                        put("title", tName)
                        put("artist", aName)
                        put("album", cName)
                        put("releaseDate", if (rawDate.length >= 10) rawDate.substring(0, 10) else rawDate)
                        put("coverUrl", art)
                        put("previewUrl", prevUrl)
                        put("duration", durSecs)
                        put("genre", item.optString("primaryGenreName", "Geral"))
                        put("source", "Apple iTunes")
                      })
                    }
                  }
                }
              }
            }
          } catch (e: Exception) {
            Log.w("SoundWaveDiscover", "Error fetching iTunes: ${e.message}")
          }
        }

        val jsonOut = resultsArray.toString()
        withContext(Dispatchers.Main) {
          val quotedBody = JSONObject.quote(jsonOut)
          val quotedCb = JSONObject.quote(callbackId)
          webView?.evaluateJavascript(
              "if (window.onOfficialDiscoverLoaded) { window.onOfficialDiscoverLoaded($quotedCb, $quotedBody); }",
              null)
        }
      }
    }

    @JavascriptInterface
    fun downloadAudioFileNative(url: String, suggestedName: String): Boolean {
      if (!url.startsWith("http://") && !url.startsWith("https://")) return false
      runOnUiThread {
        try {
          val cleanBase =
              suggestedName
                  .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                  .replace(Regex("\\s+"), " ")
                  .trim()
                  .ifEmpty { "SoundWave_Track" }
          val ext =
              when {
                cleanBase.endsWith(".mp3", true) ||
                    cleanBase.endsWith(".m4a", true) ||
                    cleanBase.endsWith(".flac", true) ||
                    cleanBase.endsWith(".ogg", true) ||
                    cleanBase.endsWith(".wav", true) -> ""
                url.contains(".flac", true) -> ".flac"
                url.contains(".m4a", true) -> ".m4a"
                url.contains(".ogg", true) -> ".ogg"
                else -> ".mp3"
              }
          val finalFileName = "$cleanBase$ext"
          val mimeType =
              when {
                finalFileName.endsWith(".flac", true) -> "audio/flac"
                finalFileName.endsWith(".m4a", true) -> "audio/mp4"
                finalFileName.endsWith(".ogg", true) -> "audio/ogg"
                else -> "audio/mpeg"
              }

          val soundWaveSubDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SoundWave")
          if (!soundWaveSubDir.exists()) {
            soundWaveSubDir.mkdirs()
          }

          val request =
              DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                setDescription("Baixando música via SoundWave")
                setTitle(finalFileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SoundWave/$finalFileName")
              }
          val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
          val downloadId = manager.enqueue(request)
          Toast.makeText(this@MainActivity, "⬇️ Download iniciado em Download/SoundWave: $finalFileName", Toast.LENGTH_SHORT).show()

          val quotedName = JSONObject.quote(finalFileName)
          val quotedUrl = JSONObject.quote(url)
          webView?.evaluateJavascript(
              "if (window.onNativeDownloadRegistered) { window.onNativeDownloadRegistered($quotedName, $quotedUrl); }",
              null)

          monitorDownloadProgress(downloadId, finalFileName, manager)
        } catch (e: Exception) {
          Log.w("SoundWaveDownload", "Error downloading audio: ${e.message}")
          Toast.makeText(this@MainActivity, "Erro ao iniciar download: ${e.message}", Toast.LENGTH_SHORT).show()
        }
      }
      return true
    }

    private var mediaPlayer: android.media.MediaPlayer? = null
    private var currentPfd: android.os.ParcelFileDescriptor? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var playAudioJob: kotlinx.coroutines.Job? = null
    private var nativeSpeed: Float = 1.0f

    @JavascriptInterface
    fun setPlaybackSpeedNative(speed: Float) {
      nativeSpeed = speed
      runOnUiThread {
        try {
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            mediaPlayer?.let { mp ->
              val params = mp.playbackParams
              params.speed = speed
              mp.playbackParams = params
            }
          }
        } catch (e: Exception) {
          Log.w("SoundWave", "Error setting playback speed native: ${e.message}")
        }
      }
    }

    private fun stopAndReleaseMediaPlayer() {
      progressJob?.cancel()
      try {
        mediaPlayer?.let { oldPlayer ->
          oldPlayer.setOnPreparedListener(null)
          oldPlayer.setOnCompletionListener(null)
          oldPlayer.setOnErrorListener(null)
          try {
            if (oldPlayer.isPlaying) oldPlayer.stop()
          } catch (_: Exception) {}
          try {
            oldPlayer.reset()
          } catch (_: Exception) {}
          oldPlayer.release()
        }
      } catch (_: Exception) {}
      mediaPlayer = null
      try { currentPfd?.close() } catch (_: Exception) {}
      currentPfd = null
    }

    @JavascriptInterface
    fun playAudioNative(url: String): Boolean {
      if (url.startsWith("magnet:")) return false
      playAudioJob?.cancel()
      playAudioJob = lifecycleScope.launch(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
          stopAndReleaseMediaPlayer()
        }

        if (!isActive) return@launch

        val uri = Uri.parse(url)
        var pfdToUse: android.os.ParcelFileDescriptor? = null
        var localPath: String? = null
        var resolvedStreamUrl: String = url

        try {
          if (url.startsWith("content://")) {
            try {
              pfdToUse = contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
              Log.w("SoundWave", "Failed to open PFD for content URI: ${e.message}")
            }
          } else if (url.startsWith("http://") || url.startsWith("https://")) {
            val hash = Math.abs(url.hashCode())
            val ext = if (url.contains(".m4a", true)) ".m4a" else ".mp3"
            val cacheFile = File(cacheDir, "audio_full_$hash$ext")

            if (cacheFile.exists() && cacheFile.length() > 30000) {
              cacheFile.setLastModified(System.currentTimeMillis())
              localPath = cacheFile.absolutePath
            } else {
              // Fast check: for short previews (< 2.5MB) cache synchronously; for full tracks stream immediately and resolve redirects
              val isLikelyPreview =
                  url.contains("itunes.apple.com", true) ||
                      url.contains("dzcdn.net", true) ||
                      url.contains("cdns-preview", true)
              if (isLikelyPreview) {
                try {
                  val req =
                      Request.Builder()
                          .url(url)
                          .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                          .build()
                  okHttpClient.newCall(req).execute().use { resp ->
                    resolvedStreamUrl = resp.request.url.toString()
                    val contentLength = resp.body?.contentLength() ?: -1L
                    if (resp.isSuccessful && contentLength in 1..2800000L) {
                      resp.body?.byteStream()?.use { input ->
                        FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                      }
                      if (cacheFile.exists() && cacheFile.length() > 30000) {
                        cacheFile.setLastModified(System.currentTimeMillis())
                        pruneAudioStreamCache(30)
                        localPath = cacheFile.absolutePath
                      }
                    }
                  }
                } catch (e: Exception) {
                  Log.w("SoundWave", "Preview cache download failed for $url: ${e.message}")
                  if (cacheFile.exists()) cacheFile.delete()
                }
              } else {
                // For full-length streams (Internet Archive, Audius, Open Audio), resolve redirect URL quickly without downloading entire file first
                try {
                  val headReq =
                      Request.Builder()
                          .url(url)
                          .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/2.0")
                          .header("Range", "bytes=0-1")
                          .build()
                  okHttpClient.newCall(headReq).execute().use { resp ->
                    resolvedStreamUrl = resp.request.url.toString()
                  }
                  cacheStreamAudioInBackground(resolvedStreamUrl, cacheFile)
                } catch (_: Exception) {
                  resolvedStreamUrl = url
                  if (cacheFile.exists() && cacheFile.length() > 30000) {
                    localPath = cacheFile.absolutePath
                  }
                }
              }
            }
          } else {
            val file = File(url)
            if (file.exists()) {
              localPath = url
            }
          }
        } catch (e: Exception) {
          Log.w("SoundWave", "Error preparing data source for $url: ${e.message}")
        }

        if (!isActive) {
          try { pfdToUse?.close() } catch (_: Exception) {}
          return@launch
        }

        withContext(Dispatchers.Main) {
          if (!isActive) {
            try { pfdToUse?.close() } catch (_: Exception) {}
            return@withContext
          }

          try {
            currentPfd = pfdToUse
            val player = android.media.MediaPlayer().apply {
              setAudioAttributes(
                  android.media.AudioAttributes.Builder()
                      .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                      .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                      .build())
            }
            mediaPlayer = player

            try {
              if (pfdToUse != null) {
                player.setDataSource(pfdToUse.fileDescriptor)
              } else if (!localPath.isNullOrEmpty()) {
                player.setDataSource(localPath)
              } else {
                if (resolvedStreamUrl.startsWith("http")) {
                  player.setDataSource(resolvedStreamUrl)
                } else {
                  player.setDataSource(applicationContext, uri)
                }
              }
            } catch (dsErr: Exception) {
               Log.e("SoundWave", "MediaPlayer setDataSource failed: ${dsErr.message}")
               throw dsErr
            }

            player.setOnPreparedListener {
              try {
                if (mediaPlayer != player) return@setOnPreparedListener
                requestAudioFocus()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && nativeSpeed != 1.0f) {
                  try {
                    val params = player.playbackParams
                    params.speed = nativeSpeed
                    player.playbackParams = params
                  } catch (_: Exception) {}
                }
                player.start()
                val dur = player.duration / 1000.0
                webView?.post {
                  webView?.evaluateJavascript(
                      "if (window.onNativeAudioPrepared) { window.onNativeAudioPrepared($dur); }",
                      null)
                }
                startNativeProgressUpdater()
              } catch (e: Exception) {
                Log.e("SoundWave", "Error starting prepared player: ${e.message}")
                notifyNativeAudioError(1, -1)
              }
            }

            player.setOnCompletionListener {
              try {
                if (mediaPlayer != player) return@setOnCompletionListener
                progressJob?.cancel()
                webView?.post {
                  webView?.evaluateJavascript(
                      "if (window.onNativeAudioEnded) { window.onNativeAudioEnded(); }", null)
                }
              } catch (e: Exception) {
                Log.e("SoundWave", "Error in onCompletion: ${e.message}")
              }
            }

            player.setOnErrorListener { _, what, extra ->
              try {
                if (mediaPlayer != player) return@setOnErrorListener true
                progressJob?.cancel()
                Log.e("SoundWave", "MediaPlayer error: what=$what, extra=$extra")
                notifyNativeAudioError(what, extra)
              } catch (_: Exception) {}
              true
            }

            player.prepareAsync()
          } catch (e: Exception) {
            Log.e("SoundWave", "Error setting up MediaPlayer: ${e.message}")
            stopAndReleaseMediaPlayer()
            notifyNativeAudioError(1, -1)
          }
        }
      }
      return true
    }

    private fun notifyNativeAudioError(what: Int, extra: Int) {
      webView?.post {
        webView?.evaluateJavascript(
            "if (window.onNativeAudioError) { window.onNativeAudioError($what, $extra); }", null)
      }
    }

    private fun startNativeProgressUpdater() {
      progressJob?.cancel()
      progressJob = lifecycleScope.launch(Dispatchers.Main) {
        while (isActive) {
          try {
            val mp = mediaPlayer
            if (mp == null || !mp.isPlaying) break
            val cur = mp.currentPosition / 1000.0
            val total = mp.duration / 1000.0
            webView?.evaluateJavascript(
                "if (window.onNativeAudioProgress) { window.onNativeAudioProgress($cur, $total); }",
                null)
          } catch (_: Exception) {
            break
          }
          delay(250)
        }
      }
    }

    @JavascriptInterface
    fun pauseAudioNative() {
      try {
        mediaPlayer?.pause()
        progressJob?.cancel()
      } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun resumeAudioNative() {
      try {
        requestAudioFocus()
        mediaPlayer?.start()
        startNativeProgressUpdater()
      } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun stopAudioNative() {
      playAudioJob?.cancel()
      stopAndReleaseMediaPlayer()
      abandonAudioFocus()
    }

    @JavascriptInterface
    fun seekAudioNative(seconds: Float) {
      try {
        val msec = (seconds * 1000).toInt()
        mediaPlayer?.let { mp ->
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            mp.seekTo(msec.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
          } else {
            mp.seekTo(msec)
          }
        }
      } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun setVolumeNative(volume: Float) {
      try {
        mediaPlayer?.setVolume(volume, volume)
      } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun isNativeAudioPlaying(): Boolean {
      return try {
        mediaPlayer?.isPlaying == true
      } catch (_: Exception) {
        false
      }
    }

    @JavascriptInterface
    fun openTorrentClient(magnetUri: String) {
      runOnUiThread { handleMagnetUrl(magnetUri) }
    }

    @JavascriptInterface
    fun showToast(message: String) {
      runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun updatePlaybackMetadata(
        title: String,
        artist: String,
        isPlaying: Boolean,
        coverUrl: String,
        durationSecs: Double,
        currentSecs: Double
    ) {
      currentTrackTitle = title.ifEmpty { "SoundWave" }
      currentTrackArtist = artist.ifEmpty { "Música" }
      isAudioPlaying = isPlaying
      currentCoverUrl = coverUrl
      val durMs = (durationSecs * 1000).toLong()
      val posMs = (currentSecs * 1000).toLong()

      runOnUiThread {
        MusicPlaybackService.startOrUpdate(
            this@MainActivity,
            currentTrackTitle,
            currentTrackArtist,
            isPlaying,
            coverUrl.ifEmpty { null },
            durMs,
            posMs
        )
        if (FloatingBubbleService.isBubbleShowing) {
          FloatingBubbleService.update(
              this@MainActivity,
              currentTrackTitle,
              isPlaying,
              coverUrl.ifEmpty { null }
          )
        }
      }
    }

    @JavascriptInterface
    fun stopPlaybackService() {
      isAudioPlaying = false
      runOnUiThread {
        MusicPlaybackService.stop(this@MainActivity)
        FloatingBubbleService.hide(this@MainActivity)
      }
    }

    @JavascriptInterface
    fun requestOverlayPermission() {
      runOnUiThread {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
          if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
            Toast.makeText(
                this@MainActivity,
                "Ative a permissão 'Aparecer sobre outros apps' para o balão flutuante",
                Toast.LENGTH_LONG
            ).show()
            try {
              val intent = Intent(
                  android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                  Uri.parse("package:$packageName")
              )
              startActivity(intent)
            } catch (_: Exception) {
              val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
              startActivity(intent)
            }
          } else {
            Toast.makeText(this@MainActivity, "Balão flutuante ativo!", Toast.LENGTH_SHORT).show()
            FloatingBubbleService.show(
                this@MainActivity,
                currentTrackTitle,
                isAudioPlaying,
                currentCoverUrl.ifEmpty { null }
            )
          }
        }
      }
    }

    @JavascriptInterface
    fun isOverlayPermissionGranted(): Boolean {
      return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        android.provider.Settings.canDrawOverlays(this@MainActivity)
      } else {
        true
      }
    }

    @JavascriptInterface
    fun toggleFloatingBubble(): Boolean {
      val prefs = getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
      val current = prefs.getBoolean("floating_bubble_enabled", true)
      val newState = !current
      prefs.edit().putBoolean("floating_bubble_enabled", newState).apply()
      runOnUiThread {
        if (newState) {
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
              !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
            requestOverlayPermission()
          } else {
            Toast.makeText(this@MainActivity, "Balão flutuante ativado!", Toast.LENGTH_SHORT).show()
          }
        } else {
          FloatingBubbleService.hide(this@MainActivity)
          Toast.makeText(this@MainActivity, "Balão flutuante desativado.", Toast.LENGTH_SHORT).show()
        }
      }
      return newState
    }

    @JavascriptInterface
    fun isFloatingBubbleEnabled(): Boolean {
      val prefs = getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
      return prefs.getBoolean("floating_bubble_enabled", true)
    }
    @JavascriptInterface
    fun minimizeAppToBackground() {
      runOnUiThread {
        moveTaskToBack(true)
      }
    }

    @JavascriptInterface
    fun checkForAppUpdateAsync(callbackId: String, customRepo: String? = null) {
      lifecycleScope.launch(Dispatchers.IO) {
        try {
          val updateInfo = AppUpdateManager.checkForUpdate(this@MainActivity, customRepo)
          val json = org.json.JSONObject().apply {
            put("hasUpdate", updateInfo.hasUpdate)
            put("currentVersionName", updateInfo.currentVersionName)
            put("currentVersionCode", updateInfo.currentVersionCode)
            put("latestVersionName", updateInfo.latestVersionName)
            put("latestVersionCode", updateInfo.latestVersionCode)
            put("downloadUrl", updateInfo.downloadUrl)
            put("changelog", updateInfo.changelog)
            put("releaseName", updateInfo.releaseName)
            put("publishedAt", updateInfo.publishedAt)
            put("repoName", updateInfo.repoName)
            put("statusMessage", updateInfo.statusMessage)
            put("statusReason", updateInfo.statusReason)
          }
          val jsonString = json.toString().replace("\\", "\\\\").replace("'", "\\'")
          runOnUiThread {
            webView?.evaluateJavascript(
                "if (window._updateCallbacks && window._updateCallbacks['$callbackId']) { window._updateCallbacks['$callbackId']('$jsonString'); }",
                null
            )
          }
        } catch (e: Exception) {
          Log.w("MainActivity", "Error in checkForAppUpdateAsync: ${e.message}")
          val fallbackJson = org.json.JSONObject().apply {
            put("hasUpdate", false)
            put("error", e.message ?: "Falha ao verificar atualização")
          }.toString().replace("\\", "\\\\").replace("'", "\\'")
          runOnUiThread {
            webView?.evaluateJavascript(
                "if (window._updateCallbacks && window._updateCallbacks['$callbackId']) { window._updateCallbacks['$callbackId']('$fallbackJson'); }",
                null
            )
          }
        }
      }
    }

    @JavascriptInterface
    fun startAppUpdateDownload(apkUrl: String) {
      lifecycleScope.launch(Dispatchers.IO) {
        AppUpdateManager.downloadAndInstall(
            this@MainActivity,
            apkUrl,
            onProgress = { percent ->
              runOnUiThread {
                webView?.evaluateJavascript(
                    "if (typeof window.onUpdateDownloadProgress === 'function') { window.onUpdateDownloadProgress($percent); }",
                    null
                )
              }
            },
            onSuccess = {
              runOnUiThread {
                webView?.evaluateJavascript(
                    "if (typeof window.onUpdateDownloadComplete === 'function') { window.onUpdateDownloadComplete(); }",
                    null
                )
              }
            },
            onError = { errMsg ->
              val escaped = errMsg.replace("'", "\\'")
              runOnUiThread {
                webView?.evaluateJavascript(
                    "if (typeof window.onUpdateDownloadError === 'function') { window.onUpdateDownloadError('$escaped'); }",
                    null
                )
              }
            }
        )
      }
    }

    @JavascriptInterface
    fun getAppVersionInfo(): String {
      val repo = AppUpdateManager.getGitHubRepo(this@MainActivity)
      return org.json.JSONObject().apply {
        put("versionName", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("githubRepo", repo)
      }.toString()
    }

    @JavascriptInterface
    fun setGitHubUpdateRepo(repo: String): Boolean {
      AppUpdateManager.setGitHubRepo(this@MainActivity, repo)
      return true
    }
  }

  /**
   * Custom WebView that keeps Chromium media decoders and JS timers active in the background
   * when the user minimizes the app while listening to YouTube streams or HTML5 audio.
   */
  private inner class BackgroundAudioWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
      if (visibility != android.view.View.VISIBLE) {
        super.onWindowVisibilityChanged(android.view.View.VISIBLE)
      } else {
        super.onWindowVisibilityChanged(visibility)
      }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
      super.onWindowFocusChanged(true)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instance = this

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
          android.content.pm.PackageManager.PERMISSION_GRANTED) {
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
      }
    }

    container = FrameLayout(this)
    setContentView(container)

    // Handle edge-to-edge window insets
    ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
      val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(0, systemBars.top, 0, systemBars.bottom)
      insets
    }

    // Back button handling: collapse expanded modal to mini-player or minimize app to background without stopping music
    onBackPressedDispatcher.addCallback(
        this,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            val wv = webView
            if (wv != null) {
              wv.evaluateJavascript(
                  "(function() { return (typeof window.handleAndroidBackButton === 'function') ? window.handleAndroidBackButton() : false; })()"
              ) { result ->
                if (result != "true") {
                  moveTaskToBack(true)
                }
              }
            } else {
              moveTaskToBack(true)
            }
          }
        })

    setupWebView()
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupWebView() {
    // Clean up previous WebView if needed
    webView?.let {
      container.removeView(it)
      it.destroy()
    }

    val newWebView = BackgroundAudioWebView(this)
    webView = newWebView
    container.addView(
        newWebView,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    newWebView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      allowFileAccess = true
      allowContentAccess = true
      @Suppress("DEPRECATION")
      allowFileAccessFromFileURLs = true
      @Suppress("DEPRECATION")
      allowUniversalAccessFromFileURLs = true
      mediaPlaybackRequiresUserGesture = false
      mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      cacheMode = WebSettings.LOAD_DEFAULT
      loadWithOverviewMode = true
      useWideViewPort = true
      displayZoomControls = false
      builtInZoomControls = false
    }

    // Bridge for torrent actions
    newWebView.addJavascriptInterface(torrentBridge, "AndroidTorrentBridge")

    newWebView.webChromeClient =
        object : WebChromeClient() {
          override fun onShowFileChooser(
              view: WebView?,
              filePathCallback: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?
          ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback

            try {
              val intent =
                  fileChooserParams?.createIntent()
                      ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addCategory(Intent.CATEGORY_OPENABLE)
                      }
              intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
              fileChooserLauncher.launch(intent)
              return true
            } catch (e: Exception) {
              this@MainActivity.filePathCallback?.onReceiveValue(null)
              this@MainActivity.filePathCallback = null
              Toast.makeText(
                      this@MainActivity,
                      "Erro ao abrir seletor de arquivos: ${e.message}",
                      Toast.LENGTH_SHORT)
                  .show()
              return false
            }
          }
        }

    newWebView.webViewClient =
        object : WebViewClient() {
          override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?
          ): Boolean {
            val url = request?.url?.toString() ?: return false
            if (url.startsWith("magnet:")) {
              handleMagnetUrl(url)
              return true
            }
            return false
          }

          @Deprecated("Deprecated in Java")
          override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (url != null && url.startsWith("magnet:")) {
              handleMagnetUrl(url)
              return true
            }
            return false
          }

          override fun shouldInterceptRequest(
              view: WebView?,
              request: WebResourceRequest?
          ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null

            if (url.startsWith("https://soundwave.app/yt_embed") || url.startsWith("http://soundwave.app/yt_embed")) {
              val videoId = (request.url.getQueryParameter("v") ?: "").replace(Regex("[^a-zA-Z0-9_-]"), "")
              val embedHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                  <meta name="referrer" content="strict-origin-when-cross-origin">
                  <script>
                    // Manter o player do YouTube ativo em segundo plano mesmo quando o app é minimizado
                    (function() {
                      try {
                        Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                        Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                        Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                        Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                        var origDocAdd = document.addEventListener.bind(document);
                        document.addEventListener = function(type, listener, options) {
                          if (type === 'visibilitychange' || type === 'webkitvisibilitychange' || type === 'pagehide') {
                            return;
                          }
                          return origDocAdd(type, listener, options);
                        };
                        var origWinAdd = window.addEventListener.bind(window);
                        window.addEventListener = function(type, listener, options) {
                          if (type === 'pagehide' || type === 'blur') {
                            return;
                          }
                          return origWinAdd(type, listener, options);
                        };
                      } catch (e) {}
                    })();
                  </script>
                  <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
                    #ytplayer { width: 100%; height: 100%; border: none; }
                  </style>
                </head>
                <body>
                  <div id="ytplayer"></div>
                  <script>
                    var player = null;
                    var progressTimer = null;
                    var initialVideoId = "$videoId";
                    var userPaused = false;
                    var isBackgroundMode = false;

                    function notifyParent(payload) {
                      try {
                        window.parent.postMessage(JSON.stringify(payload), "*");
                      } catch (e) {}
                    }

                    function startTimer() {
                      stopTimer();
                      progressTimer = setInterval(function() {
                        if (player && typeof player.getCurrentTime === 'function') {
                          var cur = player.getCurrentTime() || 0;
                          var dur = player.getDuration() || 0;
                          notifyParent({ type: 'yt_progress', current: cur, duration: dur });
                        }
                      }, 400);
                    }

                    function stopTimer() {
                      if (progressTimer) {
                        clearInterval(progressTimer);
                        progressTimer = null;
                      }
                    }

                    var tag = document.createElement('script');
                    tag.src = "https://www.youtube.com/iframe_api";
                    var firstScriptTag = document.getElementsByTagName('script')[0];
                    firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                    function onYouTubeIframeAPIReady() {
                      player = new YT.Player('ytplayer', {
                        width: '100%',
                        height: '100%',
                        videoId: initialVideoId,
                        playerVars: {
                          autoplay: 1,
                          playsinline: 1,
                          controls: 1,
                          rel: 0,
                          modestbranding: 1,
                          enablejsapi: 1,
                          vq: 'tiny',
                          origin: 'https://soundwave.app'
                        },
                        events: {
                          onReady: function(event) {
                            userPaused = false;
                            notifyParent({ type: 'yt_ready', duration: event.target.getDuration() || 0 });
                            try {
                              if (typeof event.target.setPlaybackQuality === 'function') {
                                event.target.setPlaybackQuality('tiny');
                              }
                            } catch (e) {}
                            try { event.target.playVideo(); } catch (e) {}
                            startTimer();
                          },
                          onStateChange: function(event) {
                            if (event.data === YT.PlayerState.PLAYING) {
                              userPaused = false;
                              try {
                                if (typeof event.target.setPlaybackQuality === 'function') {
                                  event.target.setPlaybackQuality('tiny');
                                }
                              } catch (e) {}
                              startTimer();
                              notifyParent({
                                type: 'yt_state',
                                state: 'playing',
                                isPlaying: true,
                                duration: player.getDuration() || 0,
                                current: player.getCurrentTime() || 0
                              });
                            } else if (event.data === YT.PlayerState.PAUSED) {
                              if (!userPaused && isBackgroundMode) {
                                setTimeout(function() {
                                  if (!userPaused && player && typeof player.playVideo === 'function') {
                                    try { player.playVideo(); } catch (e) {}
                                  }
                                }, 60);
                                return;
                              }
                              stopTimer();
                              notifyParent({
                                type: 'yt_state',
                                state: 'paused',
                                isPlaying: false,
                                duration: player.getDuration() || 0,
                                current: player.getCurrentTime() || 0
                              });
                            } else if (event.data === YT.PlayerState.ENDED) {
                              stopTimer();
                              notifyParent({ type: 'yt_ended' });
                            } else if (event.data === YT.PlayerState.BUFFERING) {
                              notifyParent({
                                type: 'yt_state',
                                state: 'buffering',
                                isPlaying: !userPaused
                              });
                            }
                          },
                          onError: function(event) {
                            stopTimer();
                            notifyParent({ type: 'yt_error', code: event.data });
                          }
                        }
                      });
                    }

                    window.addEventListener('message', function(ev) {
                      try {
                        var msg = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data;
                        if (!msg) return;
                        if (msg.cmd === 'set_bg') {
                          isBackgroundMode = Boolean(msg.value);
                          if (isBackgroundMode && !userPaused && player && typeof player.playVideo === 'function') {
                            try { player.playVideo(); } catch (e) {}
                          }
                          return;
                        }
                        if (!player) return;
                        if (msg.cmd === 'play' && typeof player.playVideo === 'function') {
                          userPaused = false;
                          player.playVideo();
                          startTimer();
                          notifyParent({
                            type: 'yt_state',
                            state: 'playing',
                            isPlaying: true,
                            duration: (typeof player.getDuration === 'function' ? player.getDuration() : 0) || 0,
                            current: (typeof player.getCurrentTime === 'function' ? player.getCurrentTime() : 0) || 0
                          });
                        } else if (msg.cmd === 'pause' && typeof player.pauseVideo === 'function') {
                          userPaused = true;
                          stopTimer();
                          player.pauseVideo();
                          notifyParent({
                            type: 'yt_state',
                            state: 'paused',
                            isPlaying: false
                          });
                        } else if (msg.cmd === 'stop' && typeof player.stopVideo === 'function') {
                          userPaused = true;
                          stopTimer();
                          player.stopVideo();
                        } else if (msg.cmd === 'seek' && typeof player.seekTo === 'function') {
                          var targetSec = (typeof msg.seconds === 'number') ? msg.seconds : (parseFloat(msg.value) || 0);
                          player.seekTo(targetSec, true);
                        } else if (msg.cmd === 'volume' && typeof player.setVolume === 'function') {
                          player.setVolume(Math.round((parseFloat(msg.value) || 1) * 100));
                        } else if (msg.cmd === 'speed' && typeof player.setPlaybackRate === 'function') {
                          player.setPlaybackRate(parseFloat(msg.value) || 1.0);
                        } else if (msg.cmd === 'quality' && typeof player.setPlaybackQuality === 'function') {
                          player.setPlaybackQuality(msg.value || 'tiny');
                        } else if (msg.cmd === 'load' && msg.videoId && typeof player.loadVideoById === 'function') {
                          userPaused = false;
                          player.loadVideoById(msg.videoId);
                          startTimer();
                        }
                      } catch (e) {}
                    });
                  </script>
                </body>
                </html>
              """.trimIndent()
              val headers = mapOf(
                  "Access-Control-Allow-Origin" to "*",
                  "Cache-Control" to "no-cache"
              )
              return WebResourceResponse(
                  "text/html",
                  "UTF-8",
                  200,
                  "OK",
                  headers,
                  java.io.ByteArrayInputStream(embedHtml.toByteArray(Charsets.UTF_8)))
            }

            if (url.startsWith("https://soundwave.local/cover") || url.startsWith("http://soundwave.local/cover")) {
              try {
                val uriStr = request.url.getQueryParameter("uri")
                if (!uriStr.isNullOrEmpty()) {
                  val decodedStr = if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) uriStr else Uri.decode(uriStr)
                  val imageUri = Uri.parse(decodedStr)
                  val mimeType = contentResolver.getType(imageUri) ?: "image/jpeg"
                  val headers = mapOf(
                      "Access-Control-Allow-Origin" to "*",
                      "Cache-Control" to "max-age=86400"
                  )
                  try {
                    val inputStream = contentResolver.openInputStream(imageUri)
                    if (inputStream != null) {
                      return WebResourceResponse(mimeType, null, 200, "OK", headers, inputStream)
                    }
                  } catch (_: Exception) {}

                  // Fallback: se for URI SAF de armazenamento primário, tenta abrir o arquivo direto
                  val fullDecoded = Uri.decode(decodedStr)
                  if (fullDecoded.contains("primary:")) {
                    val relPath = fullDecoded.substringAfterLast("primary:")
                    val directFile = File("/storage/emulated/0/$relPath")
                    if (directFile.exists() && directFile.canRead()) {
                      return WebResourceResponse(mimeType, null, 200, "OK", headers, java.io.FileInputStream(directFile))
                    }
                  }
                }
              } catch (e: Exception) {
                Log.w("SoundWaveCover", "Error serving folder cover image: ${e.message}")
              }
              return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null)
            }

            if (url.startsWith("https://soundwave.local/audio_cover") || url.startsWith("http://soundwave.local/audio_cover")) {
              try {
                val uriStr = request.url.getQueryParameter("uri")
                if (!uriStr.isNullOrEmpty()) {
                  val decodedStr = if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) uriStr else Uri.decode(uriStr)
                  val audioUri = Uri.parse(decodedStr)
                  val headers = mapOf(
                      "Access-Control-Allow-Origin" to "*",
                      "Cache-Control" to "max-age=86400"
                  )

                  // 1. Try MediaStore albumart Content URI first
                  try {
                    contentResolver.query(
                        audioUri,
                        arrayOf(android.provider.MediaStore.Audio.Media.ALBUM_ID),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                      if (cursor.moveToFirst()) {
                        val albumIdCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.ALBUM_ID)
                        if (albumIdCol != -1) {
                          val albumId = cursor.getLong(albumIdCol)
                          if (albumId > 0) {
                            val artUri = android.content.ContentUris.withAppendedId(
                                android.net.Uri.parse("content://media/external/audio/albumart"), albumId)
                            val stream = contentResolver.openInputStream(artUri)
                            if (stream != null) {
                              return WebResourceResponse("image/jpeg", null, 200, "OK", headers, stream)
                            }
                          }
                        }
                      }
                    }
                  } catch (_: Exception) {}

                  // 2. Try system-level thumbnail generator on API 29+
                  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                      val bitmap = contentResolver.loadThumbnail(audioUri, android.util.Size(400, 400), null)
                      val baos = java.io.ByteArrayOutputStream()
                      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                      return WebResourceResponse("image/jpeg", null, 200, "OK", headers, java.io.ByteArrayInputStream(baos.toByteArray()))
                    } catch (_: Exception) {}
                  }

                  // 3. Try MediaMetadataRetriever to extract embedded ID3v2 / FLAC picture
                  val retriever = android.media.MediaMetadataRetriever()
                  try {
                    retriever.setDataSource(applicationContext, audioUri)
                    val embeddedBytes = retriever.embeddedPicture
                    if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                      return WebResourceResponse("image/jpeg", null, 200, "OK", headers, java.io.ByteArrayInputStream(embeddedBytes))
                    }
                  } catch (_: Exception) {
                  } finally {
                    try { retriever.release() } catch (_: Exception) {}
                  }

                  // 4. Fallback: search folder/parent directory for loose cover image (supports MediaStore _data and SAF primary: paths)
                  try {
                    var filePath: String? = null
                    try {
                      contentResolver.query(audioUri, arrayOf("_data"), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                          val dIdx = c.getColumnIndex("_data")
                          if (dIdx != -1) filePath = c.getString(dIdx)
                        }
                      }
                    } catch (_: Exception) {}

                    if (filePath == null && audioUri.scheme == "file") {
                      filePath = audioUri.path
                    }
                    if (filePath == null) {
                      val fullDecoded = Uri.decode(decodedStr)
                      if (fullDecoded.contains("primary:")) {
                        val rel = fullDecoded.substringAfterLast("primary:")
                        filePath = "/storage/emulated/0/$rel"
                      }
                    }

                    if (!filePath.isNullOrEmpty()) {
                      val audioFile = File(filePath!!)
                      val parentDir = audioFile.parentFile
                      if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                        val imageFiles = parentDir.listFiles { _, name ->
                          name.matches(Regex(".*\\.(jpg|jpeg|png|webp|bmp|gif|jfif)$", RegexOption.IGNORE_CASE))
                        }
                        if (!imageFiles.isNullOrEmpty()) {
                          val bestImage = imageFiles.find { f ->
                            val n = f.name.lowercase()
                            n.contains("cover") || n.contains("folder") || n.contains("album") ||
                                n.contains("front") || n.contains("capa") || n.contains("art") || n.contains("thumb")
                          } ?: imageFiles.first()

                          if (bestImage.exists() && bestImage.canRead()) {
                            val mime = if (bestImage.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                            return WebResourceResponse(mime, null, 200, "OK", headers, java.io.FileInputStream(bestImage))
                          }
                        }
                      }
                    }
                  } catch (folderFallbackErr: Exception) {
                    Log.w("SoundWaveAudioCover", "Folder image fallback error: ${folderFallbackErr.message}")
                  }
                }
              } catch (e: Exception) {
                Log.w("SoundWaveAudioCover", "Error serving audio cover: ${e.message}")
              }
              return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null)
            }

            if (url.endsWith(".mp3") ||
                url.endsWith(".m4a") ||
                url.endsWith(".wav") ||
                url.endsWith(".flac") ||
                url.contains("archive.org/download") ||
                url.contains("incompetech.com/music") ||
                url.startsWith("https://audio-ssl.itunes.apple.com/") ||
                url.contains("AudioPreview")) {
              val mimeType = if (url.endsWith(".mp3") || url.contains("archive.org") || url.contains("incompetech")) "audio/mpeg" else "audio/mp4"
              val hash = Math.abs(url.hashCode())
              val ext = if (url.contains(".m4a", true)) ".m4a" else ".mp3"
              val cacheFile = File(cacheDir, "audio_full_$hash$ext")

              try {
                val okReq =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SoundWave/1.0")
                        .build()
                val okResp = okHttpClient.newCall(okReq).execute()
                val body = okResp.body
                if (body != null) {
                  val headers = mutableMapOf<String, String>()
                  for (i in 0 until okResp.headers.size) {
                    headers[okResp.headers.name(i)] = okResp.headers.value(i)
                  }
                  headers["Content-Type"] = mimeType
                  headers["Access-Control-Allow-Origin"] = "*"
                  headers["Accept-Ranges"] = "bytes"
                  if (!cacheFile.exists() && okResp.isSuccessful) {
                    cacheStreamAudioInBackground(url, cacheFile)
                  }
                  return WebResourceResponse(
                      mimeType,
                      null,
                      okResp.code,
                      "OK",
                      headers,
                      body.byteStream())
                }
              } catch (e: Exception) {
                Log.w("SoundWave", "Intercept audio error, checking cache: ${e.message}")
                if (cacheFile.exists() && cacheFile.length() > 30000) {
                  cacheFile.setLastModified(System.currentTimeMillis())
                  val headers = mutableMapOf(
                      "Content-Type" to mimeType,
                      "Access-Control-Allow-Origin" to "*",
                      "Accept-Ranges" to "bytes"
                  )
                  return WebResourceResponse(
                      mimeType,
                      null,
                      200,
                      "OK",
                      headers,
                      java.io.FileInputStream(cacheFile))
                }
              }
            }
            return super.shouldInterceptRequest(view, request)
          }

          // CRITICAL: Handle renderer crash to prevent termination of the app
          override fun onRenderProcessGone(
              view: WebView?,
              detail: RenderProcessGoneDetail?
          ): Boolean {
            val didCrash = detail?.didCrash() ?: false
            Log.e(
                "SoundWave",
                "WebView renderer process gone (didCrash=$didCrash). Recovering WebView...")

            if (isFinishing || isDestroyed) return true

            // Destroy the dead view and reinitialize
            view?.let {
              try {
                container.removeView(it)
                it.destroy()
              } catch (_: Exception) {}
            }
            webView = null

            // Re-setup the webview safely on the main thread
            container.post {
              if (!isFinishing && !isDestroyed) {
                setupWebView()
              }
            }
            return true
          }
        }

    // Handle audio downloads directly through DownloadManager
    newWebView.setDownloadListener { url, userAgent, _, mimetype, _ ->
      try {
        val fileName = url.substringAfterLast("/", "audio_download.mp3")
        val soundWaveSubDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SoundWave")
        if (!soundWaveSubDir.exists()) {
          soundWaveSubDir.mkdirs()
        }

        val request =
            DownloadManager.Request(Uri.parse(url)).apply {
              setMimeType(mimetype)
              addRequestHeader("User-Agent", userAgent)
              setDescription("Baixando via SoundWave")
              setTitle(fileName)
              setNotificationVisibility(
                  DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
              setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SoundWave/$fileName")
            }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)
        Toast.makeText(this, "⬇️ Download iniciado em Download/SoundWave: $fileName", Toast.LENGTH_SHORT).show()

        // Notify SoundWave PWA to add to library automatically
        newWebView.post {
          val quotedName = JSONObject.quote(fileName)
          val quotedUrl = JSONObject.quote(url)
          newWebView.evaluateJavascript(
              "if (window.onNativeDownloadRegistered) { window.onNativeDownloadRegistered($quotedName, $quotedUrl); }",
              null)
        }
        monitorDownloadProgress(downloadId, fileName, manager)
      } catch (e: Exception) {
        try {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
          startActivity(intent)
        } catch (_: Exception) {
          Toast.makeText(this, "Erro ao iniciar download", Toast.LENGTH_SHORT).show()
        }
      }
    }

    newWebView.loadUrl("file:///android_asset/soundwave/index.html")
  }

  private fun handleMagnetUrl(magnetUri: String) {
    try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(magnetUri))
      startActivity(intent)
      Toast.makeText(this, "Abrindo no cliente Torrent...", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
      val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clip = ClipData.newPlainText("Magnet Link", magnetUri)
      clipboard.setPrimaryClip(clip)
      Toast.makeText(
              this,
              "Nenhum cliente Torrent encontrado. Link Magnet copiado com sucesso!",
              Toast.LENGTH_LONG)
          .show()
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val action = intent.action
    if (!action.isNullOrEmpty()) {
      handleMediaAction(action)
    }
  }

  fun seekFromMediaSession(seconds: Float) {
    runOnUiThread {
      try {
        torrentBridge.seekAudioNative(seconds)
      } catch (e: Exception) {
        Log.w("SoundWave", "Direct seek error: ${e.message}")
      }
      webView?.evaluateJavascript(
          "if (typeof activeAudioMode !== 'undefined' && activeAudioMode === 'youtube' && typeof sendYouTubePlayerCommand === 'function') { sendYouTubePlayerCommand({ cmd: 'seek', seconds: $seconds }); } else if (typeof activeAudioMode !== 'undefined' && activeAudioMode === 'native' && window.AndroidTorrentBridge && typeof window.AndroidTorrentBridge.seekAudioNative === 'function') { window.AndroidTorrentBridge.seekAudioNative($seconds); } else if (typeof audio !== 'undefined') { audio.currentTime = $seconds; }",
          null
      )
    }
  }

  fun handleMediaAction(action: String) {
    runOnUiThread {
      when (action) {
        MediaControlReceiver.ACTION_PLAY_PAUSE, "ACTION_PLAY_PAUSE", Intent.ACTION_MEDIA_BUTTON -> {
          webView?.evaluateJavascript("if (typeof togglePlay === 'function') togglePlay();", null)
        }
        MediaControlReceiver.ACTION_NEXT, "ACTION_NEXT" -> {
          webView?.evaluateJavascript("if (typeof playNext === 'function') playNext();", null)
        }
        MediaControlReceiver.ACTION_PREV, "ACTION_PREV" -> {
          webView?.evaluateJavascript("if (typeof playPrev === 'function') playPrev();", null)
        }
        MediaControlReceiver.ACTION_STOP, "ACTION_STOP" -> {
          webView?.evaluateJavascript("if (typeof togglePlay === 'function' && AppState.isPlaying) togglePlay();", null)
          MusicPlaybackService.stop(this)
          FloatingBubbleService.hide(this)
        }
      }
    }
  }

  private fun notifyWebViewBackgroundState(inBackground: Boolean) {
    try {
      webView?.resumeTimers()
      webView?.evaluateJavascript(
          "if (typeof window.onAppBackgroundStateChanged === 'function') { window.onAppBackgroundStateChanged($inBackground); }",
          null
      )
    } catch (_: Exception) {}
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    notifyWebViewBackgroundState(true)
    val prefs = getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
    val bubbleEnabled = prefs.getBoolean("floating_bubble_enabled", true)
    if (isAudioPlaying) {
      MusicPlaybackService.startOrUpdate(
          this,
          currentTrackTitle,
          currentTrackArtist,
          true,
          currentCoverUrl.ifEmpty { null }
      )
      if (bubbleEnabled && android.provider.Settings.canDrawOverlays(this)) {
        FloatingBubbleService.show(
            this,
            currentTrackTitle,
            true,
            currentCoverUrl.ifEmpty { null }
        )
      }
    }
  }

  override fun onPause() {
    super.onPause()
    try {
      webView?.resumeTimers()
    } catch (_: Exception) {}
  }

  override fun onStop() {
    super.onStop()
    notifyWebViewBackgroundState(true)
    val prefs = getSharedPreferences("soundwave_prefs", Context.MODE_PRIVATE)
    val bubbleEnabled = prefs.getBoolean("floating_bubble_enabled", true)
    if (isAudioPlaying) {
      MusicPlaybackService.startOrUpdate(
          this,
          currentTrackTitle,
          currentTrackArtist,
          true,
          currentCoverUrl.ifEmpty { null }
      )
      if (bubbleEnabled && android.provider.Settings.canDrawOverlays(this)) {
        FloatingBubbleService.show(
            this,
            currentTrackTitle,
            true,
            currentCoverUrl.ifEmpty { null }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    instance = this
    notifyWebViewBackgroundState(false)
    FloatingBubbleService.hide(this)
    AppUpdateManager.resumePendingInstallIfAllowed(this)
  }

  override fun onDestroy() {
    if (instance == this) instance = null
    abandonAudioFocus()
    FloatingBubbleService.hide(this)
    webView?.let {
      container.removeView(it)
      it.destroy()
    }
    webView = null
    super.onDestroy()
  }
}
