package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MediaControlReceiver : BroadcastReceiver() {

  companion object {
    const val ACTION_PLAY_PAUSE = "com.example.soundwave.ACTION_PLAY_PAUSE"
    const val ACTION_NEXT = "com.example.soundwave.ACTION_NEXT"
    const val ACTION_PREV = "com.example.soundwave.ACTION_PREV"
    const val ACTION_STOP = "com.example.soundwave.ACTION_STOP"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    val mainActivity = MainActivity.instance
    if (mainActivity != null) {
      mainActivity.handleMediaAction(action)
    } else {
      // If activity was killed, bring it up with action
      val openIntent = Intent(context, MainActivity::class.java).apply {
        this.action = action
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      context.startActivity(openIntent)
    }
  }
}
