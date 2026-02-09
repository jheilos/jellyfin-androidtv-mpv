package org.jellyfin.playback.mpv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.playback.mpv.jni.MPVLib
import org.json.JSONArray
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a chapter in the media.
 *
 * @property index Zero-based chapter index used for navigation
 * @property title Chapter title (may be empty for untitled chapters)
 * @property time Start time of the chapter in seconds
 */
data class MpvChapter(
	val index: Int,
	val title: String,
	val time: Double,
) {
	/**
	 * Start time of the chapter as a Duration.
	 */
	val startTime: Duration
		get() = time.seconds

	/**
	 * Human-readable display name for the chapter.
	 * Falls back to "Chapter N" if no title is provided.
	 */
	val displayName: String
		get() = title.ifEmpty { "Chapter ${index + 1}" }

	/**
	 * Formatted timestamp string (HH:MM:SS or MM:SS).
	 */
	val formattedTime: String
		get() {
			val totalSeconds = time.toLong()
			val hours = totalSeconds / 3600
			val minutes = (totalSeconds % 3600) / 60
			val seconds = totalSeconds % 60
			return if (hours > 0) {
				String.format("%d:%02d:%02d", hours, minutes, seconds)
			} else {
				String.format("%d:%02d", minutes, seconds)
			}
		}
}

/**
 * Container for chapter information.
 *
 * @property chapters List of all chapters in the media
 * @property currentIndex Index of the currently playing chapter (-1 if unknown)
 */
data class MpvChapters(
	val chapters: List<MpvChapter> = emptyList(),
	val currentIndex: Int = -1,
) {
	/**
	 * Whether there are any chapters in the media.
	 */
	val hasChapters: Boolean
		get() = chapters.isNotEmpty()

	/**
	 * Number of chapters in the media.
	 */
	val count: Int
		get() = chapters.size

	/**
	 * The currently playing chapter, or null if unknown.
	 */
	val currentChapter: MpvChapter?
		get() = chapters.getOrNull(currentIndex)

	/**
	 * Whether there is a next chapter available.
	 */
	val hasNextChapter: Boolean
		get() = currentIndex >= 0 && currentIndex < chapters.size - 1

	/**
	 * Whether there is a previous chapter available.
	 */
	val hasPreviousChapter: Boolean
		get() = currentIndex > 0

	companion object {
		val EMPTY = MpvChapters()
	}
}

/**
 * Manages chapter information and navigation for mpv playback.
 *
 * This class provides:
 * - Real-time chapter information via StateFlow
 * - Methods to navigate between chapters
 * - Chapter list parsing from mpv's `chapter-list` property
 *
 * Chapter information is refreshed from mpv's properties after a file is loaded.
 *
 * ## Usage Example
 * ```kotlin
 * val chapterManager = MpvChapterManager()
 *
 * // Refresh chapters after file loads
 * chapterManager.refreshChapters()
 *
 * // Observe chapters in UI
 * chapterManager.chapters.collect { chapters ->
 *     updateChapterList(chapters.chapters)
 *     highlightCurrentChapter(chapters.currentIndex)
 * }
 *
 * // Navigate to a specific chapter
 * chapterManager.seekToChapter(3)
 *
 * // Navigate to next/previous chapter
 * chapterManager.nextChapter()
 * chapterManager.previousChapter()
 * ```
 */
class MpvChapterManager {
	private val _chapters = MutableStateFlow(MpvChapters.EMPTY)

	/**
	 * Current chapter information.
	 * Updates when [refreshChapters] or [updateCurrentChapter] is called.
	 */
	val chapters: StateFlow<MpvChapters> = _chapters.asStateFlow()

	private val _currentChapterIndex = MutableStateFlow(-1)

	/**
	 * Index of the currently playing chapter.
	 * -1 indicates no chapter is selected or chapters are unavailable.
	 */
	val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

	/**
	 * Refresh chapter information from mpv.
	 * Call this after a file is loaded or when chapters may have changed.
	 */
	fun refreshChapters() {
		try {
			val chapterListJson = MPVLib.getPropertyString("chapter-list")
			if (chapterListJson.isNullOrEmpty()) {
				Timber.d("No chapter-list available")
				_chapters.value = MpvChapters.EMPTY
				_currentChapterIndex.value = -1
				return
			}

			val parsedChapters = parseChapterList(chapterListJson)
			val currentIndex = getCurrentChapterFromMpv()

			_chapters.value = MpvChapters(
				chapters = parsedChapters,
				currentIndex = currentIndex,
			)
			_currentChapterIndex.value = currentIndex

			Timber.d("Chapters refreshed: %d chapters, current index: %d", parsedChapters.size, currentIndex)
		} catch (e: Exception) {
			Timber.e(e, "Error refreshing chapters")
			_chapters.value = MpvChapters.EMPTY
			_currentChapterIndex.value = -1
		}
	}

	/**
	 * Update only the current chapter index without re-parsing the chapter list.
	 * Useful for tracking chapter changes during playback.
	 */
	fun updateCurrentChapter() {
		try {
			val currentIndex = getCurrentChapterFromMpv()
			if (currentIndex != _currentChapterIndex.value) {
				_currentChapterIndex.value = currentIndex
				_chapters.value = _chapters.value.copy(currentIndex = currentIndex)
				Timber.d("Current chapter updated: %d", currentIndex)
			}
		} catch (e: Exception) {
			Timber.w(e, "Error updating current chapter")
		}
	}

	/**
	 * Get the current chapter index from mpv.
	 */
	private fun getCurrentChapterFromMpv(): Int {
		return try {
			MPVLib.getPropertyInt("chapter")
		} catch (e: Exception) {
			Timber.w(e, "Error getting current chapter")
			-1
		}
	}

	/**
	 * Parse mpv's chapter-list JSON into our chapter data class.
	 *
	 * Expected JSON format:
	 * ```json
	 * [
	 *   {"title": "Chapter 1", "time": 0.0},
	 *   {"title": "Chapter 2", "time": 300.5},
	 *   ...
	 * ]
	 * ```
	 */
	private fun parseChapterList(json: String): List<MpvChapter> {
		val chapters = mutableListOf<MpvChapter>()

		val jsonArray = JSONArray(json)
		for (i in 0 until jsonArray.length()) {
			val chapterObj = jsonArray.getJSONObject(i)
			val title = chapterObj.optString("title", "")
			val time = chapterObj.optDouble("time", 0.0)

			chapters.add(
				MpvChapter(
					index = i,
					title = title,
					time = time,
				)
			)
		}

		return chapters
	}

	// ========================================================================
	// Chapter Navigation
	// ========================================================================

	/**
	 * Seek to a specific chapter by index.
	 *
	 * @param index Zero-based chapter index
	 * @return true if the seek was initiated, false if the index is invalid
	 */
	fun seekToChapter(index: Int): Boolean {
		val currentChapters = _chapters.value
		if (index < 0 || index >= currentChapters.count) {
			Timber.w("Invalid chapter index: %d (total: %d)", index, currentChapters.count)
			return false
		}

		return try {
			MPVLib.setPropertyInt("chapter", index)
			_currentChapterIndex.value = index
			_chapters.value = currentChapters.copy(currentIndex = index)
			Timber.d("Seeking to chapter %d: %s", index, currentChapters.chapters.getOrNull(index)?.displayName)
			true
		} catch (e: Exception) {
			Timber.e(e, "Error seeking to chapter %d", index)
			false
		}
	}

	/**
	 * Navigate to the next chapter.
	 *
	 * @return true if navigation was initiated, false if already at the last chapter
	 */
	fun nextChapter(): Boolean {
		val currentChapters = _chapters.value
		if (!currentChapters.hasNextChapter) {
			Timber.d("No next chapter available")
			return false
		}

		return try {
			MPVLib.command(arrayOf("add", "chapter", "1"))
			// Update will be picked up by updateCurrentChapter() or property observer
			Timber.d("Navigating to next chapter")
			true
		} catch (e: Exception) {
			Timber.e(e, "Error navigating to next chapter")
			false
		}
	}

	/**
	 * Navigate to the previous chapter.
	 *
	 * @return true if navigation was initiated, false if already at the first chapter
	 */
	fun previousChapter(): Boolean {
		val currentChapters = _chapters.value
		if (!currentChapters.hasPreviousChapter) {
			Timber.d("No previous chapter available")
			return false
		}

		return try {
			MPVLib.command(arrayOf("add", "chapter", "-1"))
			// Update will be picked up by updateCurrentChapter() or property observer
			Timber.d("Navigating to previous chapter")
			true
		} catch (e: Exception) {
			Timber.e(e, "Error navigating to previous chapter")
			false
		}
	}

	/**
	 * Seek to a chapter by its start time.
	 * Finds the chapter that contains the given time and seeks to its beginning.
	 *
	 * @param time The time in seconds
	 * @return true if a chapter was found and seek was initiated
	 */
	fun seekToChapterAtTime(time: Double): Boolean {
		val currentChapters = _chapters.value
		if (!currentChapters.hasChapters) {
			return false
		}

		// Find the chapter that contains this time
		// (last chapter where time >= chapter.time)
		var targetIndex = 0
		for ((index, chapter) in currentChapters.chapters.withIndex()) {
			if (chapter.time <= time) {
				targetIndex = index
			} else {
				break
			}
		}

		return seekToChapter(targetIndex)
	}

	/**
	 * Get the chapter at a specific time.
	 *
	 * @param time The time in seconds
	 * @return The chapter at that time, or null if no chapters exist
	 */
	fun getChapterAtTime(time: Double): MpvChapter? {
		val currentChapters = _chapters.value
		if (!currentChapters.hasChapters) {
			return null
		}

		var result: MpvChapter? = null
		for (chapter in currentChapters.chapters) {
			if (chapter.time <= time) {
				result = chapter
			} else {
				break
			}
		}
		return result
	}

	/**
	 * Get the next chapter relative to the current position.
	 *
	 * @return The next chapter, or null if at the last chapter
	 */
	fun getNextChapter(): MpvChapter? {
		val currentChapters = _chapters.value
		val nextIndex = currentChapters.currentIndex + 1
		return currentChapters.chapters.getOrNull(nextIndex)
	}

	/**
	 * Get the previous chapter relative to the current position.
	 *
	 * @return The previous chapter, or null if at the first chapter
	 */
	fun getPreviousChapter(): MpvChapter? {
		val currentChapters = _chapters.value
		val prevIndex = currentChapters.currentIndex - 1
		return if (prevIndex >= 0) currentChapters.chapters.getOrNull(prevIndex) else null
	}

	/**
	 * Clear all chapter information and reset state.
	 * Call this when stopping playback or loading a new file.
	 */
	fun clear() {
		_chapters.value = MpvChapters.EMPTY
		_currentChapterIndex.value = -1
	}
}
