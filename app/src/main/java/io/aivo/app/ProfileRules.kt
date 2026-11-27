package io.aivo.app

internal object ProfileRules {
    const val MAX_DISPLAY_NAME_LENGTH = 40

    fun normalizeDisplayName(value: String): String =
        value.trim().replace(WHITESPACE, " ").take(MAX_DISPLAY_NAME_LENGTH)

    fun firstName(value: String): String? =
        normalizeDisplayName(value).substringBefore(' ').takeIf(String::isNotBlank)

    private val WHITESPACE = Regex("\\s+")
}
