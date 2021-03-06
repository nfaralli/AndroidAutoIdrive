package me.hufman.androidautoidrive.music.controllers

import android.content.Context
import android.os.DeadObjectException
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import me.hufman.androidautoidrive.music.*
import java.util.*

/**
 * Wraps a MediaController with a handy interface
 * Any function may throw DeadObjectException, please catch it
 */
class GenericMusicAppController(val context: Context, val mediaController: MediaControllerCompat, val musicBrowser: MusicBrowser?) : MusicAppController {
	// forward any callbacks to the UI
	private val controllerCallback by lazy {
		object : MediaControllerCompat.Callback() {
			override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
				callback?.invoke(this@GenericMusicAppController)
			}

			override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
				callback?.invoke(this@GenericMusicAppController)
			}

			override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
				callback?.invoke(this@GenericMusicAppController)
			}
		}
	}
	val TAG = "GenericMusicController"
	var connected = true
	var callback: ((MusicAppController) -> Unit)? = null    // UI listener

	private inline fun remoteCall(runnable: () -> Unit) {
		try {
			return runnable()
		} catch (e: DeadObjectException) {
			Log.w(TAG, "Received DeadObjectException from MediaController $this")
			this.disconnectController()
		}
	}
	private inline fun <T> remoteData(runnable: () -> T): T? {
		try {
			return runnable()
		} catch (e: DeadObjectException) {
			Log.w(TAG, "Received DeadObjectException from MediaController $this")
			this.disconnectController()
			return null
		}
	}

	override fun play() = remoteCall {
		mediaController.transportControls.play()
	}

	override fun pause() = remoteCall {
		mediaController.transportControls.pause()
	}

	override fun skipToPrevious() = remoteCall {
		mediaController.transportControls.skipToPrevious()
	}

	override fun skipToNext() = remoteCall {
		mediaController.transportControls.skipToNext()
	}

	override fun seekTo(newPos: Long) = remoteCall {
		mediaController.transportControls.seekTo(newPos)
	}

	override fun playSong(song: MusicMetadata) = remoteCall {
		if (song.mediaId != null) {
			mediaController.transportControls.playFromMediaId(song.mediaId, song.extras)
		}
	}

	override fun playQueue(song: MusicMetadata) = remoteCall {
		if (song.queueId != null) {
			mediaController.transportControls.skipToQueueItem(song.queueId)
		}
	}

	override fun playFromSearch(search: String) = remoteCall {
		mediaController.transportControls.playFromSearch(search, null)
	}

	override fun customAction(action: CustomAction) = remoteCall {
		if (action.packageName == mediaController.packageName) {
			triggerSpotifyWorkaround()

			mediaController.transportControls?.sendCustomAction(action.action, action.extras)
		}
	}

	/* Current state */
	override fun getQueue(): List<MusicMetadata> {
		triggerSpotifyWorkaround()

		return remoteData {
			mediaController.queue?.map { MusicMetadata.fromQueueItem(it) }
		} ?: LinkedList()
	}

	override fun getMetadata(): MusicMetadata? = remoteData {
		mediaController.metadata?.let {
			MusicMetadata.fromMediaMetadata(it, mediaController.playbackState)
		}
	}

	override fun getPlaybackPosition(): PlaybackPosition {
		val state = remoteData { mediaController.playbackState }
		return if (state == null) {
			PlaybackPosition(true, 0, 0, 0)
		} else {
			val metadata = getMetadata()
			val isPaused = (
					state.state == PlaybackStateCompat.STATE_PAUSED ||
					state.state == PlaybackStateCompat.STATE_CONNECTING ||
					state.state == PlaybackStateCompat.STATE_BUFFERING
					)
			PlaybackPosition(isPaused, state.lastPositionUpdateTime, state.position, metadata?.duration ?: -1)
		}
	}

	override fun isSupportedAction(action: MusicAction): Boolean {
		val actions = remoteData { mediaController.playbackState?.actions } ?: 0
		return (actions and action.flag) > 0
	}

	override fun getCustomActions(): List<CustomAction> {
		triggerSpotifyWorkaround()

		return remoteData { mediaController.playbackState?.customActions }?.map {
			CustomAction.fromMediaCustomAction(context, mediaController.packageName, it)
		} ?: LinkedList()
	}

	/**
	 * Spotify does not post the queue or custom actions to the MediaSession normally
	 * Instead, it only updates the queue and custom actions as part of a browse loadChildren
	 */
	private fun triggerSpotifyWorkaround() {
		// spotify needs a browse to update metadata, such as queue and custom actions
		if (musicBrowser?.musicAppInfo?.packageName == "com.spotify.music" && musicBrowser.connected) {
			GlobalScope.launch(musicBrowser.handler.asCoroutineDispatcher()) {
				musicBrowser.browse(null, 200)
			}
		}
	}

	override suspend fun browse(directory: MusicMetadata?): List<MusicMetadata> {
		val app = musicBrowser
		return remoteData { app?.browse(directory?.mediaId)?.map {
			MusicMetadata.fromMediaItem(it)
		} } ?: LinkedList()
	}

	override suspend fun search(query: String): List<MusicMetadata>? = remoteData {
		val app = musicBrowser
		return app?.search(query)?.map {
			MusicMetadata.fromMediaItem(it)
		}
	}

	override fun subscribe(callback: (MusicAppController) -> Unit) {
		this.callback = callback
		mediaController.registerCallback(this.controllerCallback)
	}

	override fun isConnected(): Boolean {
		return this.connected
	}

	private fun disconnectController() {
		this.connected = false
		try {
			mediaController.unregisterCallback(this.controllerCallback)
		} catch (e: Exception) {}
		try {
			musicBrowser?.disconnect()
		} catch (e: Exception) {}
		callback?.invoke(this)
	}

	override fun disconnect() {
		this.callback = null
		disconnectController()
	}

	override fun toString(): String {
		return "GenericMusicAppController(${mediaController.packageName},${musicBrowser?.connected ?: false})"
	}
}