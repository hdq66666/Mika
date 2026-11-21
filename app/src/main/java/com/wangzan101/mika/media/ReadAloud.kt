package com.wangzan101.mika.media

import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings

/** Check the current master switch before resolving a provider or synthesizing speech. */
internal suspend fun readAloud(
    text: String,
    settings: VoiceSettings,
    model: (VoiceChoice, TtsCapabilities) -> TtsModel?,
    play: suspend (TtsModel, TtsRequest, Boolean) -> Unit,
) {
    val state = settings.state.value
    if (!state.enabled) return
    val choice = state.choice ?: return
    val caps = settings.capabilities() ?: return
    val tts = model(choice, caps) ?: return
    play(
        tts,
        TtsRequest(text.take(state.maxCharsPerSpeak), choice.voice.ifBlank { null }, choice.speed, "wav"),
        choice.streaming,
    )
}
