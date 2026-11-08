package com.example

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun multiSourceSearchEngine_hasYouTubePrimaryAndPreservedFeatures() {
        val assetCandidates = listOf(
            File("src/main/assets/soundwave/index.html"),
            File("app/src/main/assets/soundwave/index.html")
        )
        val indexFile = assetCandidates.firstOrNull { it.exists() }
        assertTrue("index.html asset must exist", indexFile != null && indexFile.exists())
        val content = indexFile!!.readText()

        // 1. Primary YouTube provider + 6 auxiliary/open/torrent providers exist
        assertTrue(content.contains("async function searchProviderYouTube("))
        assertTrue(content.contains("async function searchProviderItunes("))
        assertTrue(content.contains("async function searchProviderDeezer("))
        assertTrue(content.contains("async function searchProviderInternetArchive("))
        assertTrue(content.contains("async function searchProviderAudius("))
        assertTrue(content.contains("async function searchProviderOpenAudio("))
        assertTrue(content.contains("async function searchProviderBitTorrent("))

        // 2. Official Embedded YouTube IFrame Player & content separation
        assertTrue(content.contains("ytEmbedContainer"))
        assertTrue(content.contains("ytEmbedFrame"))
        assertTrue(content.contains("https://soundwave.app/yt_embed"))
        assertTrue(content.contains("🎵 ÁUDIO COMPLETO"))
        assertTrue(content.contains("⬇ DOWNLOAD"))
        assertTrue(content.contains("🧲 TORRENT"))
        assertTrue(content.contains("🎧 PRÉVIA 30s"))

        // 3. Parallel isolated execution with Promise.allSettled & deduplication/ranking
        assertTrue(content.contains("Promise.allSettled(["))
        assertTrue(content.contains("function deduplicateAndRankMultiSourceResults("))

        // 4. Data Saver / Audio Only mode (144p tiny resolution + cover art)
        assertTrue(content.contains("ytAudioOnlyMode: true"))
        assertTrue(content.contains("ytAudioModeBtn"))

        // 5. Quick filter bar & preserved features (Library, Favorites, Playlists, Magnet)
        assertTrue(content.contains("multiSearchFilterBar"))
        assertTrue(content.contains("function toggleSaveTrack("))
        assertTrue(content.contains("function renderFoldersView("))
        assertTrue(content.contains("function openMagnetLink("))
        assertTrue(content.contains("function downloadAudioDirect("))
    }

    @Test
    fun discoverSection_meetsAllRequirementsAndIntegrations() {
        val assetCandidates = listOf(
            File("src/main/assets/soundwave/index.html"),
            File("app/src/main/assets/soundwave/index.html")
        )
        val indexFile = assetCandidates.firstOrNull { it.exists() }
        assertTrue("index.html asset must exist", indexFile != null && indexFile.exists())
        val htmlContent = indexFile!!.readText()

        // 1. Bottom Nav Button for Descobrir
        assertTrue(htmlContent.contains("id=\"navDiscoverBtn\""))
        assertTrue(htmlContent.contains("data-tab=\"discoverTab\""))
        assertTrue(htmlContent.contains("<span>Descobrir</span>"))

        // 2. Discover Tab Container & Header Controls
        assertTrue(htmlContent.contains("id=\"discoverTab\""))
        assertTrue(htmlContent.contains("id=\"refreshDiscoverBtn\""))
        assertTrue(htmlContent.contains("id=\"discoverLastUpdatedText\""))
        assertTrue(htmlContent.contains("id=\"discoverSearchShortcut\""))

        // 3. Genre Filters (Brasil, Internacional, Sertanejo, Funk, Pagode, Gospel, Pop, Rock, Rap, Eletrônica)
        val genres = listOf("brasil", "internacional", "sertanejo", "funk", "pagode", "gospel", "pop", "rock", "rap", "eletronica")
        genres.forEach { g ->
            assertTrue("Genre chip data-genre='$g' must exist", htmlContent.contains("data-genre=\"$g\""))
        }

        // 4. The 5 Main Required Sections
        assertTrue(htmlContent.contains("id=\"sectionWeeklyReleases\""))
        assertTrue(htmlContent.contains("id=\"weeklyReleasesCarousel\""))
        assertTrue(htmlContent.contains("id=\"weeklyReleasesList\""))

        assertTrue(htmlContent.contains("id=\"sectionYouTubeNew\""))
        assertTrue(htmlContent.contains("id=\"youtubeNewList\""))

        assertTrue(htmlContent.contains("id=\"sectionCoversAlternatives\""))
        assertTrue(htmlContent.contains("id=\"coversAlternativesList\""))

        assertTrue(htmlContent.contains("id=\"sectionTrendingBrazil\""))
        assertTrue(htmlContent.contains("id=\"trendingBrazilList\""))

        assertTrue(htmlContent.contains("id=\"sectionIndependentDiscover\""))
        assertTrue(htmlContent.contains("id=\"independentDiscoverList\""))

        // 5. JavaScript Discover Engine & Bridge Callbacks
        assertTrue(htmlContent.contains("window.onYouTubeDiscoverLoaded"))
        assertTrue(htmlContent.contains("window.onOfficialDiscoverLoaded"))
        assertTrue(htmlContent.contains("loadDiscoverSection"))
        assertTrue(htmlContent.contains("renderDiscoverAllSections"))
        assertTrue(htmlContent.contains("soundwave_discover_cache"))

        // 6. Native Bridge in MainActivity.kt
        val mainCandidates = listOf(
            File("src/main/java/com/example/MainActivity.kt"),
            File("app/src/main/java/com/example/MainActivity.kt")
        )
        val mainFile = mainCandidates.firstOrNull { it.exists() }
        assertTrue("MainActivity.kt must exist", mainFile != null && mainFile.exists())
        val mainContent = mainFile!!.readText()

        assertTrue(mainContent.contains("fun fetchYouTubeDiscoverAsync("))
        assertTrue(mainContent.contains("fun fetchOfficialChartsAndReleasesAsync("))
        assertTrue(mainContent.contains("isOfficialMusicChannelOrRelease"))
        assertTrue(mainContent.contains("publishedAfter"))

        // 7. Official Channel Filtering & Clean Card UI
        assertTrue(htmlContent.contains("isOfficialYouTubeChannel"))

        // 8. Descobrir as default initial screen & Playback sequence rules (YouTube genre-only & Folder/All-Folders Shuffle)
        assertTrue(htmlContent.contains("<section id=\"discoverTab\" class=\"tab-pane active\">"))
        assertTrue(htmlContent.contains("class=\"bottom-nav-item active\" data-tab=\"discoverTab\" id=\"navDiscoverBtn\""))
        assertTrue(htmlContent.contains("function getScopedYouTubeGenreTracks("))
        assertTrue(htmlContent.contains("function getScopedFolderOrDownloadTracks("))
        assertTrue(htmlContent.contains("function getAllFoldersAndDownloadsPool("))
        assertTrue(htmlContent.contains("function buildDiscoverGenreSequence("))

        // 9. Fade-in animation
        assertTrue(htmlContent.contains("@keyframes discoverFadeIn"))
        assertTrue(htmlContent.contains("discover-fade-in"))
        assertTrue(htmlContent.contains("function applyDiscoverCardFadeIn("))
        assertTrue(mainContent.contains("isNew24h"))

        // 10. Background playback, Mini Player persistence for YouTube & Play/Pause button state sync
        assertTrue(mainContent.contains("class BackgroundAudioWebView"))
        assertTrue(mainContent.contains("notifyWebViewBackgroundState"))
        assertTrue(htmlContent.contains("yt-keep-alive"))
        assertTrue(htmlContent.contains("window.onAppBackgroundStateChanged"))
        assertTrue(htmlContent.contains("window.handleAndroidBackButton"))
        assertTrue(htmlContent.contains("function updatePlayIcons("))

        // 11. Strict Genre Organization (Internacional, Rock vs Gospel, etc.), 3-dots menu on song cards & Mini Player local cover sync
        assertTrue(htmlContent.contains("function getDiscoverQueriesForGenre("))
        assertTrue(htmlContent.contains("function matchesDiscoverGenre("))
        assertTrue(htmlContent.contains("card-more-btn"))
        assertTrue(htmlContent.contains("card-dropdown-menu"))
        assertTrue(htmlContent.contains("menu-download-btn"))
        assertTrue(htmlContent.contains("triggerMiniCoverFallback"))
    }

    @Test
    fun downloadAndImportImprovements_workAsExpected() {
        val assetCandidates = listOf(
            File("src/main/assets/soundwave/index.html"),
            File("app/src/main/assets/soundwave/index.html")
        )
        val indexFile = assetCandidates.firstOrNull { it.exists() }
        assertTrue("index.html asset must exist", indexFile != null && indexFile.exists())
        val htmlContent = indexFile!!.readText()

        val mainCandidates = listOf(
            File("src/main/java/com/example/MainActivity.kt"),
            File("app/src/main/java/com/example/MainActivity.kt")
        )
        val mainFile = mainCandidates.firstOrNull { it.exists() }
        assertTrue("MainActivity.kt must exist", mainFile != null && mainFile.exists())
        val mainContent = mainFile!!.readText()

        // 1. Download/SoundWave directory destination and monitoring
        assertTrue(mainContent.contains("Download/SoundWave"))
        assertTrue(mainContent.contains("monitorDownloadProgress("))

        // 2. Direct download improvements for Archive, Audius, Jamendo
        assertTrue(htmlContent.contains("downloadUrl") || htmlContent.contains("downloadAudioDirect"))

        // 3. Import MP3 button and event listeners
        assertTrue(mainContent.contains("pickAudioFiles()"))

        // 4. Download progress, completion and error callbacks
        assertTrue(mainContent.contains("window.onNativeDownloadProgress") || htmlContent.contains("window.onNativeDownloadRegistered"))
        assertTrue(mainContent.contains("window.onNativeDownloadComplete") || htmlContent.contains("window.onNativeDownloadRegistered"))
        assertTrue(mainContent.contains("window.onNativeDownloadError") || htmlContent.contains("window.onNativeDownloadRegistered"))
    }

    @Test
    fun serviceWorker_audioStreamCachingAndOfflineListening_worksAsExpected() {
        val swCandidates = listOf(
            File("src/main/assets/soundwave/sw.js"),
            File("app/src/main/assets/soundwave/sw.js")
        )
        val swFile = swCandidates.firstOrNull { it.exists() }
        assertTrue("sw.js asset must exist", swFile != null && swFile.exists())
        val swContent = swFile!!.readText()

        // 1. Service Worker cache names & LRU limit
        assertTrue(swContent.contains("soundwave-static-v2"))
        assertTrue(swContent.contains("soundwave-audio-v1"))
        assertTrue(swContent.contains("MAX_AUDIO_ITEMS = 30"))

        // 2. Audio stream identification and HTTP 206 Range request handling
        assertTrue(swContent.contains("isAudioStreamRequest("))
        assertTrue(swContent.contains("handleAudioRangeRequest("))
        assertTrue(swContent.contains("status: 206"))
        assertTrue(swContent.contains("Content-Range"))
        assertTrue(swContent.contains("Accept-Ranges"))

        // 3. Audio fetch caching and offline fallback
        assertTrue(swContent.contains("handleAudioFetch("))
        assertTrue(swContent.contains("trimAudioCache("))

        // 4. Client-side integration in index.html
        val indexCandidates = listOf(
            File("src/main/assets/soundwave/index.html"),
            File("app/src/main/assets/soundwave/index.html")
        )
        val indexFile = indexCandidates.firstOrNull { it.exists() }
        assertTrue("index.html asset must exist", indexFile != null && indexFile.exists())
        val htmlContent = indexFile!!.readText()

        assertTrue(htmlContent.contains("setupPWA()") || htmlContent.contains("serviceWorker"))

        // 5. Native stream cache in MainActivity.kt
        val mainCandidates = listOf(
            File("src/main/java/com/example/MainActivity.kt"),
            File("app/src/main/java/com/example/MainActivity.kt")
        )
        val mainFile = mainCandidates.firstOrNull { it.exists() }
        assertTrue("MainActivity.kt must exist", mainFile != null && mainFile.exists())
        val mainContent = mainFile!!.readText()

        assertTrue(mainContent.contains("fun cacheStreamAudioInBackground("))
        assertTrue(mainContent.contains("fun pruneAudioStreamCache("))
        assertTrue(mainContent.contains("audio_full_"))
    }

    @Test
    fun coverLoader_andFloatingBubble_workAsExpected() {
        val bubbleCandidates = listOf(
            File("src/main/java/com/example/FloatingBubbleService.kt"),
            File("app/src/main/java/com/example/FloatingBubbleService.kt")
        )
        val bubbleFile = bubbleCandidates.firstOrNull { it.exists() }
        assertTrue("FloatingBubbleService.kt must exist", bubbleFile != null && bubbleFile.exists())
        val bubbleContent = bubbleFile!!.readText()

        assertTrue("Must support EXTRA_COVER_URL", bubbleContent.contains("EXTRA_COVER_URL"))
        assertTrue("Must update cover art", bubbleContent.contains("updateCoverArt("))
        assertTrue("Must clip to outline", bubbleContent.contains("clipToOutline = true"))

        val serviceCandidates = listOf(
            File("src/main/java/com/example/MusicPlaybackService.kt"),
            File("app/src/main/java/com/example/MusicPlaybackService.kt")
        )
        val serviceFile = serviceCandidates.firstOrNull { it.exists() }
        assertTrue("MusicPlaybackService.kt must exist", serviceFile != null && serviceFile.exists())
        val serviceContent = serviceFile!!.readText()

        assertTrue("Must set METADATA_KEY_ALBUM_ART", serviceContent.contains("METADATA_KEY_ALBUM_ART"))
        assertTrue("Must set METADATA_KEY_ART", serviceContent.contains("METADATA_KEY_ART"))
        assertTrue("Must set METADATA_KEY_DISPLAY_ICON", serviceContent.contains("METADATA_KEY_DISPLAY_ICON"))
    }
}



