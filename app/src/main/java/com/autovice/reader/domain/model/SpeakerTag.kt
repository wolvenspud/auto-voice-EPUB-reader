package com.autovice.reader.domain.model

sealed class SpeakerTag {

    object Narrator : SpeakerTag()

    /** 「」or 『』content without a resolved character name. */
    object UnknownDialogue : SpeakerTag()

    /** （）content — inner thought, uses narrator voice but flagged separately. */
    object InnerMonologue : SpeakerTag()

    /** 「「「」」」or group-attributed speech. */
    object Group : SpeakerTag()

    data class Character(val name: String) : SpeakerTag()

    fun toStorageString(): String = when (this) {
        is Narrator -> "narrator"
        is UnknownDialogue -> "unknown_dialogue"
        is InnerMonologue -> "inner_monologue"
        is Group -> "group"
        is Character -> "character:$name"
    }

    companion object {
        fun fromStorageString(value: String): SpeakerTag = when {
            value == "narrator" -> Narrator
            value == "unknown_dialogue" -> UnknownDialogue
            value == "inner_monologue" -> InnerMonologue
            value == "group" -> Group
            value.startsWith("character:") -> Character(value.removePrefix("character:"))
            else -> Narrator
        }
    }
}
