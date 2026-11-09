package com.mymav.miniapps.conversate

/**
 * Compiles prep notes + pasted materials (ported from maverick-ai-hello conversate tree).
 */
object BriefingPack {

    private val urlPattern = Regex("""https?://[^\s)>"]+""", RegexOption.IGNORE_CASE)

    fun compile(prepNotes: String, briefingMaterials: String, mode: MeetingMode): String {
        val urls = extractUrls(prepNotes + "\n" + briefingMaterials)
        val bullets = extractBullets(briefingMaterials)
        return buildString {
            appendLine("Meeting mode: ${mode.label} — ${mode.promptHint}")
            if (prepNotes.isNotBlank()) {
                appendLine()
                appendLine("Prep notes:")
                appendLine(prepNotes.take(1500))
            }
            if (briefingMaterials.isNotBlank()) {
                appendLine()
                appendLine("Briefing materials:")
                appendLine(briefingMaterials.take(3500))
            }
            if (bullets.isNotEmpty()) {
                appendLine()
                appendLine("Key bullets (index):")
                bullets.take(12).forEach { appendLine("• $it") }
            }
            if (urls.isNotEmpty()) {
                appendLine()
                appendLine("Reference URLs:")
                urls.take(8).forEach { appendLine("- $it") }
            }
        }.trim().take(5000)
    }

    fun extractUrls(text: String): List<String> =
        urlPattern.findAll(text).map { it.value.trimEnd('.', ',') }.distinct().take(10).toList()

    private fun extractBullets(materials: String): List<String> =
        materials.lines()
            .map { it.trim() }
            .filter { line ->
                line.length in 4..200 && (
                    line.startsWith("-") || line.startsWith("•") || line.startsWith("*") ||
                        Regex("""^\d+[\).]\s""").containsMatchIn(line)
                    )
            }
            .map { it.removePrefix("-").removePrefix("•").removePrefix("*").trim() }
            .distinct()
            .take(20)
}
