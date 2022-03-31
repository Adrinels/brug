package com.github.brugapp.brug.model

import java.time.LocalDateTime

data class ChatImage(
    val imageURI: String,
    override val sender: String,
    override val mid: Int,
    override val timestamp: LocalDateTime,
    override val body: String
) : Message(mid, timestamp, body, sender)