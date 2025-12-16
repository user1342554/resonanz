package com.resonanz.app.utils

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun formatAudioMetaString(mimeType: String?, bitrate: Int?, sampleRate: Int?): String {
    val bitrateKbps = bitrate?.div(1000) ?: 0
    val sampleRateKHz = (sampleRate ?: 0) / 1000.0
    val format = mimeTypeToFormat(mimeType)
    return "$format • ${bitrateKbps} kb/s • $sampleRateKHz kHz"
}

fun mimeTypeToFormat(mimeType: String?): String {
    return when {
        mimeType == null -> "Unknown"
        mimeType.contains("flac", ignoreCase = true) -> "FLAC"
        mimeType.contains("mp3", ignoreCase = true) || mimeType.contains("mpeg", ignoreCase = true) -> "MP3"
        mimeType.contains("aac", ignoreCase = true) -> "AAC"
        mimeType.contains("ogg", ignoreCase = true) -> "OGG"
        mimeType.contains("wav", ignoreCase = true) -> "WAV"
        mimeType.contains("m4a", ignoreCase = true) -> "M4A"
        else -> mimeType.substringAfterLast("/").uppercase()
    }
}

