package com.mymav.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder

/**
 * Foreground microphone service for the meeting recorder. The ongoing notification is the phone's visible recording
 * indicator and carries a Stop action. Audio is raw AAC (ADTS), which stays playable if the process is killed.
 */
class MeetingRecorderService : Service() {
    private var recorder: MediaRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_FILE).orEmpty())
            ACTION_STOP -> { MyMavMeeting.stopFromNotification(); }
            ACTION_FINISH -> finish()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Meeting recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, MeetingRecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MyMav is recording a meeting")
            .setContentText("Say stop recording, or tap Stop.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startRecording(path: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, notification())
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(path)
            r.prepare()
            r.start()
            recorder = r
            MyMavMeeting.onRecorderStarted()
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            MyMavMeeting.onRecorderFailed("The microphone couldn't start (${e.javaClass.simpleName}). Another app may be using it.")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun finish() {
        var ok = true
        try { recorder?.stop() } catch (e: RuntimeException) { ok = false }
        runCatching { recorder?.release() }
        recorder = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        MyMavMeeting.onRecorderFinished(ok)
    }

    override fun onDestroy() {
        // Killed or stopped by the system while recording: the app will mark the recording as interrupted on next start.
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mymav.app.meeting.START"
        const val ACTION_STOP = "com.mymav.app.meeting.STOP"
        const val ACTION_FINISH = "com.mymav.app.meeting.FINISH"
        const val EXTRA_FILE = "file"
        private const val CHANNEL = "meeting_recording"
        private const val NOTIF_ID = 4107
    }
}
