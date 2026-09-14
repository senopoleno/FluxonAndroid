package io.github.p1neapplexpress.openflux.data

enum class TransportType {
    yandex,
    vyandex,
    max,
    cups;

    companion object {
        fun from(raw: String): TransportType =
            entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) ||
                (it == cups && (raw.equals("cupsonline", ignoreCase = true) || raw.equals("cups.online", ignoreCase = true)))
            } ?: yandex
    }
}
