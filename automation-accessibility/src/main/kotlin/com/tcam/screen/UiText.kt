package com.tcam.screen

import com.tcam.core.input.TitleNormalizer

/**
 * Text matching for UI labels. Telegram labels are localised, so every
 * selector is a set of known translations (English + Persian). Matching is
 * case-insensitive and ignores invisible characters.
 */
object UiText {
    fun norm(s: CharSequence?): String = TitleNormalizer.normalize(s?.toString().orEmpty())
        .replace("…", "").replace("...", "").trim()

    fun equalsAny(text: CharSequence?, options: Collection<String>): Boolean {
        val t = norm(text)
        return t.isNotEmpty() && options.any { norm(it) == t }
    }

    fun containsAny(text: CharSequence?, options: Collection<String>): Boolean {
        val t = norm(text)
        return t.isNotEmpty() && options.any { val o = norm(it); o.isNotEmpty() && t.contains(o) }
    }

    private val COUNT = Regex("([0-9][0-9.,\\s]*)\\s*([kKmM]|هزار|میلیون)?\\s*(subscribers?|members?|مشترک|عضو)", RegexOption.IGNORE_CASE)

    /** Parses "12,345 subscribers", "1.2K subscribers", "۱۲ مشترک". Returns null when absent. */
    fun subscriberCount(text: CharSequence?): Int? {
        val t = norm(text)
        val m = COUNT.find(t) ?: return null
        val numberText = m.groupValues[1].replace(" ", "").trim()
        val suffix = m.groupValues[2].lowercase()
        val multiplier = when (suffix) {
            "k", "هزار" -> 1_000.0
            "m", "میلیون" -> 1_000_000.0
            else -> 1.0
        }
        val value = if (multiplier > 1.0) {
            numberText.replace(',', '.').toDoubleOrNull()
        } else {
            numberText.replace(",", "").replace(".", "").toDoubleOrNull()
        } ?: return null
        return (value * multiplier).toInt()
    }

    private val USERNAME_IN_TEXT = Regex("(?:@|t\\.me/)([A-Za-z][A-Za-z0-9_]{3,31})")

    /** Finds a @username or t.me/username inside a label. */
    fun usernameIn(text: CharSequence?): String? = USERNAME_IN_TEXT.find(text?.toString().orEmpty())?.groupValues?.get(1)
}

/** Known labels in the official Telegram Android app (English and Persian). */
object Labels {
    val SEARCH = setOf("Search", "جستجو", "جستجو کنید")
    val MORE_OPTIONS = setOf("More options", "گزینه‌های بیشتر", "گزینه های بیشتر")
    val EDIT = setOf("Edit", "ویرایش")
    val LEAVE_CHANNEL = setOf("Leave channel", "Leave Channel", "ترک کانال")
    val LEAVE_CONFIRM = setOf("Leave channel", "Leave Channel", "Leave", "ترک کانال", "ترک", "خروج")
    val DELETE_CHANNEL = setOf("Delete channel", "Delete Channel", "حذف کانال")
    val DELETE_CONFIRM = setOf("Delete channel", "Delete Channel", "Delete", "حذف کانال", "حذف")
    val JOIN = setOf("Join", "Join Channel", "Subscribe", "عضویت", "پیوستن", "عضویت در کانال", "پیوستن به کانال")
    val MEMBER_BAR = setOf("Mute", "Unmute", "بی‌صدا", "صدادار", "بی صدا")
    val CHANNEL_WORD = setOf("channel", "subscribers", "subscriber", "کانال", "مشترک")
    val NOT_FOUND = setOf(
        "username doesn't exist", "username does not exist", "doesn't seem to exist", "Username not found",
        "this channel can't be displayed", "Invite link expired", "This invite link is invalid",
        "نام کاربری وجود ندارد", "یافت نشد", "منقضی شده",
    )
}
