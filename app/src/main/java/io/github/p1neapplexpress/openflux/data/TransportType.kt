package io.github.p1neapplexpress.openflux.data

enum class TransportType(val cliName: String) {
    yandex("yandex"),
    vyandex("vyandex"),
    max("oneme"),
    cups("cupsonline"),
    mailru("mailru");

    companion object {
        fun from(raw: String): TransportType =
            entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) ||
                it.cliName.equals(raw, ignoreCase = true) ||
                (it == cups && (raw.equals("cupsonline", ignoreCase = true) || raw.equals("cups.online", ignoreCase = true))) ||
                (it == max && raw.equals("oneme", ignoreCase = true)) ||
                (it == mailru && (raw.equals("mail", ignoreCase = true) || raw.equals("mail.ru", ignoreCase = true) || raw.equals("mail_ru", ignoreCase = true)))
            } ?: yandex
    }
}
