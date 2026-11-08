package com.example

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object CoverLoader {
  private const val TAG = "CoverLoader"
  private const val MAX_DIMENSION = 512

  // 12MB in-memory LRU cache
  private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int {
      return value.byteCount
    }
  }

  fun getCached(url: String?): Bitmap? {
    if (url.isNullOrEmpty()) return null
    return memoryCache.get(url)
  }

  fun putCache(url: String, bitmap: Bitmap) {
    if (url.isNotEmpty()) {
      memoryCache.put(url, bitmap)
    }
  }

  fun loadBitmap(context: Context, urlStr: String?): Bitmap? {
    if (urlStr.isNullOrBlank()) return null

    // 1. Check memory cache first
    memoryCache.get(urlStr)?.let { return it }

    val rawBitmap: Bitmap? = try {
      when {
        urlStr.contains("soundwave.local") -> loadSoundwaveLocal(context, urlStr)
        urlStr.startsWith("content://") || urlStr.startsWith("file://") -> loadLocalUri(context, Uri.parse(urlStr))
        urlStr.startsWith("http://") || urlStr.startsWith("https://") -> loadRemoteUrl(urlStr)
        else -> null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed loading bitmap for $urlStr: ${e.message}")
      null
    }

    val scaled = rawBitmap?.let { scaleDownIfNeeded(it, MAX_DIMENSION) }
    if (scaled != null) {
      memoryCache.put(urlStr, scaled)
    }
    return scaled
  }

  private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= maxDim && height <= maxDim) return bitmap

    val ratio = width.toFloat() / height.toFloat()
    val newWidth: Int
    val newHeight: Int
    if (width > height) {
      newWidth = maxDim
      newHeight = (maxDim / ratio).toInt().coerceAtLeast(1)
    } else {
      newHeight = maxDim
      newWidth = (maxDim * ratio).toInt().coerceAtLeast(1)
    }

    return try {
      Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    } catch (_: Exception) {
      bitmap
    }
  }

  private fun loadLocalUri(context: Context, uri: Uri): Bitmap? {
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun loadRemoteUrl(urlStr: String): Bitmap? {
    // Primary attempt
    val primaryBitmap = fetchHttpBitmap(urlStr)
    if (primaryBitmap != null) return primaryBitmap

    // YouTube specific fallback: if hqdefault fails, try mqdefault, 0.jpg, or sddefault
    if (urlStr.contains("ytimg.com") || urlStr.contains("youtube.com")) {
      val videoId = extractYouTubeVideoId(urlStr)
      if (videoId != null) {
        val candidates = listOf(
            "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/0.jpg",
            "https://i.ytimg.com/vi/$videoId/default.jpg"
        )
        for (altUrl in candidates) {
          if (altUrl != urlStr) {
            val altBmp = fetchHttpBitmap(altUrl)
            if (altBmp != null) return altBmp
          }
        }
      }
    }

    return null
  }

  private fun extractYouTubeVideoId(urlStr: String): String? {
    val regex = Regex("/vi/([^/]+)/")
    val match = regex.find(urlStr)
    return match?.groupValues?.getOrNull(1)
  }

  private fun fetchHttpBitmap(urlStr: String): Bitmap? {
    var connection: HttpURLConnection? = null
    return try {
      val url = URL(urlStr)
      connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        instanceFollowRedirects = true
        doInput = true
        setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
      }
      connection.connect()
      if (connection.responseCode in 200..299) {
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isNotEmpty()) {
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
          null
        }
      } else {
        null
      }
    } catch (e: Exception) {
      Log.d(TAG, "HTTP fetch failed for $urlStr: ${e.message}")
      null
    } finally {
      connection?.disconnect()
    }
  }

  private fun loadSoundwaveLocal(context: Context, urlStr: String): Bitmap? {
    val parsedUri = Uri.parse(urlStr)
    val targetUriStr = parsedUri.getQueryParameter("uri") ?: return null
    val decodedStr = if (targetUriStr.startsWith("content://") || targetUriStr.startsWith("file://")) {
      targetUriStr
    } else {
      Uri.decode(targetUriStr)
    }
    val localUri = Uri.parse(decodedStr)

    if (urlStr.contains("audio_cover")) {
      // 1. MediaStore album art
      try {
        context.contentResolver.query(
            localUri,
            arrayOf(MediaStore.Audio.Media.ALBUM_ID),
            null,
            null,
            null
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            if (albumIdCol != -1) {
              val albumId = cursor.getLong(albumIdCol)
              if (albumId > 0) {
                val artUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                context.contentResolver.openInputStream(artUri)?.use { stream ->
                  val bmp = BitmapFactory.decodeStream(stream)
                  if (bmp != null) return bmp
                }
              }
            }
          }
        }
      } catch (_: Exception) {}

      // 2. System thumbnail on API 29+
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
          val thumb = context.contentResolver.loadThumbnail(localUri, android.util.Size(MAX_DIMENSION, MAX_DIMENSION), null)
          if (thumb != null) return thumb
        } catch (_: Exception) {}
      }

      // 3. MediaMetadataRetriever embedded picture
      val retriever = MediaMetadataRetriever()
      try {
        if (localUri.scheme == "file" && localUri.path != null) {
          retriever.setDataSource(localUri.path)
        } else {
          try {
            retriever.setDataSource(context, localUri)
          } catch (_: Exception) {
            context.contentResolver.openFileDescriptor(localUri, "r")?.use { pfd ->
              retriever.setDataSource(pfd.fileDescriptor)
            }
          }
        }
        val embedded = retriever.embeddedPicture
        if (embedded != null && embedded.isNotEmpty()) {
          val bmp = BitmapFactory.decodeByteArray(embedded, 0, embedded.size)
          if (bmp != null) return bmp
        }
      } catch (_: Exception) {
      } finally {
        try { retriever.release() } catch (_: Exception) {}
      }

      // 4. Loose image in parent folder
      try {
        var filePath: String? = null
        try {
          context.contentResolver.query(localUri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
              val dIdx = c.getColumnIndex("_data")
              if (dIdx != -1) filePath = c.getString(dIdx)
            }
          }
        } catch (_: Exception) {}

        if (filePath == null && localUri.scheme == "file") {
          filePath = localUri.path
        }
        if (filePath == null) {
          val fullDecoded = Uri.decode(decodedStr)
          if (fullDecoded.contains("primary:")) {
            val rel = fullDecoded.substringAfterLast("primary:")
            filePath = "/storage/emulated/0/$rel"
          }
        }

        if (!filePath.isNullOrEmpty()) {
          val audioFile = File(filePath)
          val parentDir = audioFile.parentFile
          if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
            val imageCandidates = parentDir.listFiles { f ->
              f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "jfif")
            }
            val bestImg = imageCandidates?.firstOrNull()
            if (bestImg != null) {
              val bmp = BitmapFactory.decodeFile(bestImg.absolutePath)
              if (bmp != null) return bmp
            }
          }
        }
      } catch (_: Exception) {}

      return null
    } else {
      // "soundwave.local/cover"
      try {
        context.contentResolver.openInputStream(localUri)?.use { stream ->
          val bmp = BitmapFactory.decodeStream(stream)
          if (bmp != null) return bmp
        }
      } catch (_: Exception) {}

      if (localUri.scheme == "file" && localUri.path != null) {
        val f = File(localUri.path!!)
        if (f.exists()) return BitmapFactory.decodeFile(f.absolutePath)
      }

      val fullDecoded = Uri.decode(decodedStr)
      if (fullDecoded.contains("primary:")) {
        val rel = fullDecoded.substringAfterLast("primary:")
        val f = File("/storage/emulated/0/$rel")
        if (f.exists()) return BitmapFactory.decodeFile(f.absolutePath)
      }

      return null
    }
  }
}
