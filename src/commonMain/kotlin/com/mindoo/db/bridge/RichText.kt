package com.mindoo.db.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One element of a rich-text field (MindooDB `MindooRichTextSpan`): a run of
 * characters with their marks, or a structural block marker (paragraph,
 * heading, list item, ...). Walk the spans in order to render the field.
 */
@Serializable
sealed class RichTextSpan {
    /** Characters with the inline formatting that applies to all of them, e.g. `{"bold": true}`. */
    @Serializable
    @SerialName("text")
    data class Text(val value: String, val marks: JsonObject? = null) : RichTextSpan()

    /** A structural boundary; [value] holds the block's attributes, e.g. `{"type": "paragraph"}`. */
    @Serializable
    @SerialName("block")
    data class Block(val value: JsonObject) : RichTextSpan()
}

/** A rich-text field as read by [DocumentApi.getRichText]; pass [heads] as `baseHeads` of the next edit. */
@Serializable
data class RichTextSnapshot(
    val path: List<JsonElement> = emptyList(),
    val heads: List<String>,
    val spans: List<RichTextSpan>,
)

/** Marks for `length` characters starting at `index`. */
@Serializable
data class RichTextMarkRange(val index: Int, val length: Int, val marks: JsonObject)

/** A positional rich-text edit (MindooDB `MindooRichTextStep`). */
@Serializable
sealed class RichTextStep {
    /** Delete [deleteCount] characters at [index], insert [insert] there and apply [marks]. */
    @Serializable
    @SerialName("splice")
    data class Splice(
        val index: Int,
        val deleteCount: Int = 0,
        val insert: String? = null,
        val marks: List<RichTextMarkRange>? = null,
    ) : RichTextStep()
}

/** A character position for [DocumentApi.getTextCursors]: an index, or the start/end of the text. */
sealed class TextCursorPosition {
    internal abstract fun toJson(): JsonElement

    data class At(val index: Int) : TextCursorPosition() {
        override fun toJson(): JsonElement = JsonPrimitive(index)
    }

    data object Start : TextCursorPosition() {
        override fun toJson(): JsonElement = JsonPrimitive("start")
    }

    /** Keeps pointing past the last character when text is appended. */
    data object End : TextCursorPosition() {
        override fun toJson(): JsonElement = JsonPrimitive("end")
    }
}

/** Stable cursors: opaque strings that keep pointing at the same character while text changes around them. */
@Serializable
data class TextCursors(
    val path: List<JsonElement> = emptyList(),
    val heads: List<String>,
    val cursors: List<String>,
)

/** Character indexes of resolved cursors, in request order. */
@Serializable
data class TextCursorPositions(
    val path: List<JsonElement> = emptyList(),
    val heads: List<String>,
    val positions: List<Int>,
)
