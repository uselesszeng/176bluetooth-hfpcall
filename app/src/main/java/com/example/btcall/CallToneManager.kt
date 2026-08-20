package com.example.btcall

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator

object CallToneManager {
    private var ringtone: Ringtone? = null
    private var outgoingPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var isPlayingIncoming = false
    private var isPlayingOutgoing = false

    fun playIncomingRingtone(context: Context) {
        if (isPlayingIncoming) return
        stopAll()
        isPlayingIncoming = true
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, ringtoneUri)?.apply {
                play()
            }
        } catch (e: Exception) {
            try {
                val altUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                outgoingPlayer = MediaPlayer.create(context, altUri)?.apply {
                    isLooping = true
                    start()
                }
            } catch (ex: Exception) {}
        }
    }

    fun playOutgoingRingtone(context: Context) {
        if (isPlayingOutgoing) return
        stopAll()
        isPlayingOutgoing = true
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            Thread {
                try {
                    while (isPlayingOutgoing) {
                        toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                        Thread.sleep(1500)
                        toneGenerator?.stopTone()
                        Thread.sleep(3000)
                    }
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {
            try {
                val ringbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                outgoingPlayer = MediaPlayer.create(context, ringbackUri)?.apply {
                    isLooping = true
                    setVolume(0.3f, 0.3f)
                    start()
                }
            } catch (ex: Exception) {}
        }
    }

    fun stopAll() {
        isPlayingIncoming = false
        isPlayingOutgoing = false
        
        try {
            ringtone?.stop()
        } catch (e: Exception) {}
        ringtone = null

        try {
            outgoingPlayer?.stop()
            outgoingPlayer?.release()
        } catch (e: Exception) {}
        outgoingPlayer = null

        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (e: Exception) {}
        toneGenerator = null
    }
}
