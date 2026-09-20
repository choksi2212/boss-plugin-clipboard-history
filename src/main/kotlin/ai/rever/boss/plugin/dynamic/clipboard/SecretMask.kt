package ai.rever.boss.plugin.dynamic.clipboard

/**
 * Secret pattern detection for clipboard capture.
 *
 * The host clipboard is whatever the user (or any app on the machine) most
 * recently wrote to it - it is not a permissioned surface. The plugin runs
 * in the same JVM as the host, so a regex match is the cheapest gate between
 * a copied secret and it sitting in the user's plugin storage. The intent
 * here is "plain credentials pasted into chat get hidden", not "obfuscated
 * credentials cannot be captured": any captured entry is one reveal-click
 * away from an agent or another plugin.
 *
 * The pattern matches the same broad strokes as env-inspector:
 * /SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|API_KEY|BEGIN [A-Z ]*PRIVATE KEY/i
 * applied to the candidate text. A match means the entry is stored as the
 * literal placeholder [MASKED_PLACEHOLDER] and the original text is
 * discarded - there is nothing to reveal afterwards, only the placeholder.
 *
 * Pattern is case-insensitive and runs on the full text, not line-by-line,
 * so a copy that includes a `password=` line anywhere is masked.
 */
internal object SecretMask {
    const val MASKED_PLACEHOLDER: String = "<masked>"

    private val pattern: Regex = Regex(
        pattern = "SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|API_KEY|BEGIN [A-Z ]*PRIVATE KEY",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * True if [text] should be masked before being stored. The check runs on
     * the original text exactly as it was read from the clipboard - any
     * further normalization (truncation, trim) is the caller's job.
     */
    fun isSecret(text: String): Boolean = pattern.containsMatchIn(text)

    /**
     * Return [MASKED_PLACEHOLDER] if [text] looks like a secret, otherwise
     * return [text] unchanged. The original text is not retained, so a
     * masked entry has nothing to reveal.
     */
    fun mask(text: String): String = if (isSecret(text)) MASKED_PLACEHOLDER else text
}
