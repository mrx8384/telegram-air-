package com.tcam.core.input

import com.tcam.core.model.ChannelInput

/**
 * Turns free text (one channel per line, or comma separated) into [ChannelInput]s.
 *
 * Recognised forms:
 * - `@name`
 * - `t.me/name`, `https://t.me/name`, `telegram.me/name`, `telegram.dog/name`, `t.me/s/name`
 * - `name.t.me`
 * - `tg://resolve?domain=name`
 * - `t.me/+hash`, `t.me/joinchat/hash`, `tg://join?invite=hash` (invite links)
 * - anything else is treated as a channel title
 */
object ChannelInputParser {

    /** Telegram usernames: 4–32 chars, start with a letter, letters/digits/underscores. */
    private val USERNAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")
    private val HOSTS = setOf("t.me", "telegram.me", "telegram.dog")
    /** "keyword: news" or "کلمه: اخبار" means: every channel whose name contains the word. */
    val KEYWORD_PREFIXES = listOf("keyword:", "کلمه:", "کلیدواژه:")
    private val RESERVED_PATHS = setOf("joinchat", "addstickers", "addemoji", "share", "proxy", "socks", "login", "setlanguage", "addtheme", "c", "iv", "bg", "invoice", "boost")

    fun parse(text: String): List<ChannelInput> {
        val seen = LinkedHashMap<String, ChannelInput>()
        text.split('\n', '\r', ',', '،')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val input = parseOne(line) ?: return@forEach
                seen.putIfAbsent(dedupeKey(input), input)
            }
        return seen.values.toList()
    }

    /** Parses a single entry. Returns null for entries that are empty after cleaning. */
    fun parseOne(entry: String): ChannelInput? {
        val raw = entry.trim()
        if (raw.isEmpty()) return null

        KEYWORD_PREFIXES.firstOrNull { raw.startsWith(it, ignoreCase = true) }?.let { prefix ->
            val word = raw.substring(prefix.length).trim()
            return if (word.isEmpty()) null else ChannelInput.Keyword(word.replace(Regex("\\s+"), " "), raw)
        }

        if (raw.startsWith("@")) {
            val name = raw.removePrefix("@").trim()
            return if (isValidUsername(name)) ChannelInput.Username(name, raw) else ChannelInput.Title(raw, raw)
        }

        parseTgScheme(raw)?.let { return it }
        parseHttpLink(raw)?.let { return it }
        // A Telegram link we cannot use (message link, sticker pack, ...) is not a title.
        if (looksLikeTelegramLink(raw)) return null

        val title = raw.replace(Regex("\\s+"), " ")
        return ChannelInput.Title(title, raw)
    }

    fun isValidUsername(name: String): Boolean =
        USERNAME.matches(name) && !name.endsWith("_") && !name.contains("__")

    private fun looksLikeTelegramLink(raw: String): Boolean {
        val s = raw.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val host = s.substringBefore('/').substringBefore('?')
        return s.startsWith("tg://") || host in HOSTS || host.endsWith(".t.me")
    }

    private fun parseTgScheme(raw: String): ChannelInput? {
        if (!raw.lowercase().startsWith("tg://")) return null
        val rest = raw.substring(5)
        val path = rest.substringBefore('?').lowercase()
        val params = queryParams(rest.substringAfter('?', ""))
        return when (path) {
            "resolve" -> params["domain"]?.takeIf(::isValidUsername)?.let { ChannelInput.Username(it, raw) }
            "join" -> params["invite"]?.takeIf { it.isNotBlank() }?.let { ChannelInput.InviteLink("https://t.me/+$it", raw) }
            else -> null
        }
    }

    private fun parseHttpLink(raw: String): ChannelInput? {
        var s = raw
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("HTTPS://").removePrefix("HTTP://")
        s = s.removePrefix("www.")
        val hostPart = s.substringBefore('/').substringBefore('?').lowercase()

        // name.t.me form
        if (hostPart.endsWith(".t.me") && !s.contains('/')) {
            val name = hostPart.removeSuffix(".t.me")
            return if (isValidUsername(name)) ChannelInput.Username(name, raw) else null
        }
        if (hostPart !in HOSTS) return null

        val segments = s.substringAfter('/', "").substringBefore('?').substringBefore('#')
            .split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        val first = segments[0]
        if (first.startsWith("+")) {
            val hash = first.removePrefix("+")
            return if (hash.isNotBlank()) ChannelInput.InviteLink("https://t.me/+$hash", raw) else null
        }
        if (first.equals("joinchat", ignoreCase = true)) {
            val hash = segments.getOrNull(1) ?: return null
            return ChannelInput.InviteLink("https://t.me/+$hash", raw)
        }
        val name = if (first.equals("s", ignoreCase = true)) segments.getOrNull(1) ?: return null else first
        if (name.lowercase() in RESERVED_PATHS) return null
        return if (isValidUsername(name)) ChannelInput.Username(name, raw) else null
    }

    private fun queryParams(query: String): Map<String, String> =
        query.split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k.lowercase() to v
        }.toMap()

    private fun dedupeKey(input: ChannelInput): String = when (input) {
        is ChannelInput.Username -> "u:" + input.username.lowercase()
        is ChannelInput.InviteLink -> "i:" + input.link
        is ChannelInput.Title -> "t:" + TitleNormalizer.normalize(input.title)
        is ChannelInput.Keyword -> "k:" + TitleNormalizer.normalize(input.keyword)
    }

    /** Text form that [parseOne] turns back into a keyword input. */
    fun keywordRaw(word: String): String = "keyword: ${word.trim()}"
}

/** Normalises titles so that harmless differences (case, spacing, Arabic/Persian letter variants) do not matter. */
object TitleNormalizer {
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    fun normalize(title: String): String {
        val sb = StringBuilder()
        INVISIBLE.replace(title, "").lowercase().forEach { c ->
            sb.append(
                when (c) {
                    'ي', 'ى' -> 'ی'
                    'ك' -> 'ک'
                    'ة' -> 'ه'
                    'ۀ' -> 'ه'
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                },
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
