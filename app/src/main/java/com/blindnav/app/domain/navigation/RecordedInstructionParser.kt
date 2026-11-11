package com.blindnav.app.domain.navigation

import com.blindnav.app.data.db.entity.EventType
import java.text.Normalizer
import java.util.Locale

/** Classification only; the user's actual sentence is stored unchanged. */
object RecordedInstructionParser {
    private fun normalized(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")

    fun type(text: String): EventType {
        val words = normalized(text)
        return when {
            Regex("\\b(gira|giro|girar|dobla|doblar)\\b").containsMatchIn(words) -> EventType.TURN
            Regex("\\b(paso de cebra|paso de peatones|cruce|cruzar)\\b").containsMatchIn(words) -> EventType.CROSSING
            else -> EventType.INFO
        }
    }

    fun locksHeading(text: String, type: EventType): Boolean =
        type == EventType.CROSSING || (type == EventType.INFO &&
            Regex("\\b(recto|recta)\\b").containsMatchIn(normalized(text)))

    fun turnSide(text: String): Int? {
        val side = Regex("\\b(?:gira|giro|girar|dobla|doblar)(?:\\s+a)?(?:\\s+la)?\\s+(derecha|izquierda)\\b")
            .find(normalized(text))?.groupValues?.get(1) ?: return null
        return if (side == "derecha") 1 else -1
    }
}
