package com.agentdeck.data.model

/**
 * Region grouping for the built-in site catalogue.
 *
 * The region drives the card accent colour and the corner badge, but never
 * carries information on its own: the badge text ("CN" / "VPN") always repeats
 * the region so the grouping never depends on colour perception alone.
 */
enum class SiteRegion(val code: String, val badge: String, val accentArgb: Long) {
    /** Mainland China, reachable without a VPN. */
    CN("CN", "CN", 0xFF4D6BFE),

    /** Overseas services that normally require a VPN in mainland China. */
    OVERSEAS("VPN", "VPN", 0xFFD9433B);

    companion object {
        fun fromCode(code: String): SiteRegion =
            entries.firstOrNull { it.code == code } ?: CN
    }
}

/**
 * A single agent host entry shown on the home grid.
 *
 * @param id stable identifier; also seeds the WebView profile name so that
 *   login state is isolated per card.
 * @param url the page loaded in the session WebView.
 * @param alternateUrl optional second entry point (e.g. the international
 *   mirror of a service) that the user can switch to from the card menu.
 */
data class SiteCard(
    val id: String,
    val name: String,
    val url: String,
    val region: SiteRegion,
    val iconKey: String,
    val adapterId: String,
    val alternateUrl: String? = null,
    val alternateLabel: String? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
) {
    /** Profile name used by androidx.webkit multi-profile support. */
    val profileName: String get() = "agentdeck-$id"
}
