package ru.sokolniki.electricity.domain

/** Содержит версию, выводимую в журнале при запуске обоих приложений. */
object BuildInfo {
    const val VERSION = "1.0.14"

    /** Формирует заметный разделитель начала нового экземпляра приложения в журнале. */
    fun startupBanner(applicationName: String): String = """
        ╔════════════════════════════════════════════════════════════╗
        ║        ЭЛЕКТРОСЧЁТ Т1 / Т2 — $applicationName
        ║        Версия $VERSION
        ╚════════════════════════════════════════════════════════════╝
    """.trimIndent()
}
