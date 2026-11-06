package com.agentdeck.data.model

/**
 * The 13 built-in agent sites.
 *
 * Colour rules (see [SiteRegion]):
 *  - card background is always surfaceContainer
 *  - CN cards use #4D6BFE, overseas cards use #D9433B
 *  - the accent colour is applied to the border and the icon plate; icon and
 *    label text stay white on top of the accent
 *  - the corner badge repeats the region in text so colour is never the only
 *    carrier of meaning
 */
object BuiltInSites {

    private fun cn(
        id: String,
        name: String,
        url: String,
        iconKey: String,
        order: Int,
        alternateUrl: String? = null,
        alternateLabel: String? = null,
    ) = SiteCard(
        id = id,
        name = name,
        url = url,
        region = SiteRegion.CN,
        iconKey = iconKey,
        adapterId = id,
        alternateUrl = alternateUrl,
        alternateLabel = alternateLabel,
        sortOrder = order,
    )

    private fun overseas(
        id: String,
        name: String,
        url: String,
        iconKey: String,
        order: Int,
    ) = SiteCard(
        id = id,
        name = name,
        url = url,
        region = SiteRegion.OVERSEAS,
        iconKey = iconKey,
        adapterId = id,
        sortOrder = order,
    )

    /** Mainland China block, 9 entries. */
    val chinaSites: List<SiteCard> = listOf(
        cn("deepseek", "DeepSeek", "https://chat.deepseek.com", "deepseek", 0),
        cn("kimi", "Kimi", "https://kimi.moonshot.cn", "kimi", 1),
        cn("doubao", "豆包", "https://www.doubao.com/chat", "doubao", 2),
        cn("lingguang", "灵光", "https://www.lingguang.com", "lingguang", 3),
        cn("yuanbao", "元宝", "https://yuanbao.tencent.com", "yuanbao", 4),
        cn(
            id = "chatglm",
            name = "智谱清言",
            url = "https://chatglm.cn",
            iconKey = "chatglm",
            order = 5,
            alternateUrl = "https://z.ai",
            alternateLabel = "国际版 z.ai",
        ),
        cn("qwen", "通义千问", "https://chat.qwen.ai", "qwen", 6),
        cn("yiyan", "文心一言", "https://yiyan.baidu.com", "yiyan", 7),
        cn("nvidia", "英伟达", "https://build.nvidia.com/models", "nvidia", 8),
    )

    /** Overseas block, 4 entries. */
    val overseasSites: List<SiteCard> = listOf(
        overseas("chatgpt", "ChatGPT", "https://chatgpt.com", "chatgpt", 0),
        overseas("gemini", "Gemini", "https://gemini.google.com", "gemini", 1),
        overseas("claude", "Claude", "https://claude.ai", "claude", 2),
        overseas("grok", "Grok", "https://grok.com", "grok", 3),
    )

    /** Full catalogue, China block first. */
    val all: List<SiteCard> = chinaSites + overseasSites
}
