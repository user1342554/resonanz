package com.resonanz.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Lyrics(
    val plain: List<String>? = null,
    val synced: List<SyncedLine>? = null,
    val areFromRemote: Boolean = false
)

@Serializable
data class SyncedLine(
    val time: Int,
    val line: String,
    val words: List<SyncedWord>? = null
)

@Serializable
data class SyncedWord(val time: Int, val word: String)

