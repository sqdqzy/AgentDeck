package com.agentdeck.data.repo

import com.agentdeck.data.db.dao.SiteAdapterDao
import com.agentdeck.data.db.dao.SiteCardDao
import com.agentdeck.data.db.entity.SiteAdapterEntity
import com.agentdeck.data.db.entity.SiteCardEntity
import com.agentdeck.data.model.BuiltInSites
import com.agentdeck.data.model.SiteCard
import com.agentdeck.data.model.SiteRegion
import com.agentdeck.web.DoneSignal
import com.agentdeck.web.InputStrategy
import com.agentdeck.web.SendStrategy
import com.agentdeck.web.SiteAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the site cards and their adapters.
 *
 * The built-in catalogue is written to the database on first launch so that
 * user edits survive restarts; the catalogue itself remains the source of
 * truth for the defaults.
 */
@Singleton
class SiteRepository @Inject constructor(
    private val cardDao: SiteCardDao,
    private val adapterDao: SiteAdapterDao,
) {

    /** All cards, ordered. */
    val cards: Flow<List<SiteCard>> = cardDao.observeAll().map { list ->
        list.map { it.toModel() }
    }

    /** All adapters, keyed by id. */
    val adapters: Flow<Map<String, SiteAdapter>> = adapterDao.observeAll().map { list ->
        list.associate { it.id to SiteAdapter.fromJson(it.json) }
    }

    /** Seeds the built-in catalogue when the database is empty. */
    suspend fun seedIfEmpty() {
        if (cardDao.count() > 0) return
        val now = System.currentTimeMillis()
        cardDao.upsertAll(BuiltInSites.all.map { it.toEntity() })
        for (card in BuiltInSites.all) {
            adapterDao.upsert(
                SiteAdapterEntity(
                    id = card.adapterId,
                    label = card.name,
                    json = SiteAdapter.toJson(defaultAdapterFor(card.id)),
                    updatedAt = now,
                ),
            )
        }
    }

    /** Loads a single card. */
    suspend fun card(id: String): SiteCard? = cardDao.getById(id)?.toModel()

    /** Loads a single adapter. */
    suspend fun adapter(id: String): SiteAdapter =
        adapterDao.getById(id)?.let { SiteAdapter.fromJson(it.json) }
            ?: defaultAdapterFor(id)

    /** Persists an edited card. */
    suspend fun saveCard(card: SiteCard) {
        cardDao.upsert(card.toEntity())
    }

    /** Persists an edited adapter. */
    suspend fun saveAdapter(id: String, label: String, adapter: SiteAdapter) {
        adapterDao.upsert(
            SiteAdapterEntity(
                id = id,
                label = label,
                json = SiteAdapter.toJson(adapter),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Deletes a user-created card. */
    suspend fun deleteCard(id: String) {
        cardDao.delete(id)
    }

    /**
     * Per-site defaults.
     *
     * The selectors target the common structure of each site's chat UI. They
     * are best-effort starting points; the calibration tool in the settings
     * screen exists precisely because real pages drift.
     */
    private fun defaultAdapterFor(siteId: String): SiteAdapter = when (siteId) {
        "deepseek" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button.send-button",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div#message-container",
            lastMessageSelector = "div.message-text",
            assistantRoleAttr = "data-role",
            doneSignal = DoneSignal.TEXT_STABLE,
            stopButtonSelector = null,
        )

        "kimi" -> SiteAdapter(
            inputSelector = "div[contenteditable=\"true\"]",
            inputStrategy = InputStrategy.CONTENTEDITABLE,
            sendSelector = "button.send-btn",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.chat-message-container",
            lastMessageSelector = "div.chat-message",
            assistantRoleAttr = "data-role",
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "doubao" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button.button-send",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.flow-message",
            lastMessageSelector = "div.flow-message",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "lingguang" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button.send-btn",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.chat-content",
            lastMessageSelector = "div.chat-item",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "yuanbao" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button.send-icon",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.agent-chat__list",
            lastMessageSelector = "div.agent-chat__bubble--ai",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "chatglm" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button[aria-label*=\"发送\"]",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.message-list",
            lastMessageSelector = "div.message-item",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "qwen" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button.chat-submit",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.qwen-chat",
            lastMessageSelector = "div.qwen-chat__message--ai",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "yiyan" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "div.chat-submit",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.chat-message",
            lastMessageSelector = "div.chat-message",
            assistantRoleAttr = "data-type",
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "nvidia" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button[data-testid=\"send-button\"]",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div.chat-container",
            lastMessageSelector = "div.chat-message",
            assistantRoleAttr = "data-role",
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "chatgpt" -> SiteAdapter(
            inputSelector = "#prompt-textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button[data-testid=\"send-button\"]",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div#thread",
            lastMessageSelector = "div[data-message-author-role=\"assistant\"]",
            assistantRoleAttr = "data-message-author-role",
            doneSignal = DoneSignal.STOP_BUTTON_GONE,
            stopButtonSelector = "button[data-testid=\"stop-button\"]",
        )

        "gemini" -> SiteAdapter(
            inputSelector = "rich-textarea .ql-editor",
            inputStrategy = InputStrategy.CONTENTEDITABLE,
            sendSelector = "button.send-button",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div#conversation-turns",
            lastMessageSelector = "model-response",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "claude" -> SiteAdapter(
            inputSelector = "div[contenteditable=\"true\"][role=\"textbox\"]",
            inputStrategy = InputStrategy.CONTENTEDITABLE,
            sendSelector = "button[data-testid=\"send-message\"]",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div[role=\"main\"]",
            lastMessageSelector = "div[data-testid=\"user-message\"] ~ div",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        "grok" -> SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button[data-testid=\"send-button\"]",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "div[data-testid=\"primary-column\"]",
            lastMessageSelector = "div[data-testid=\"message\"]",
            assistantRoleAttr = null,
            doneSignal = DoneSignal.TEXT_STABLE,
        )

        else -> SiteAdapter.defaults
    }
}

/** Maps the database row to the domain model. */
internal fun SiteCardEntity.toModel() = SiteCard(
    id = id,
    name = name,
    url = url,
    region = SiteRegion.fromCode(regionCode),
    iconKey = iconKey,
    adapterId = adapterId,
    alternateUrl = alternateUrl,
    alternateLabel = alternateLabel,
    sortOrder = sortOrder,
    enabled = enabled,
)

/** Maps the domain model to the database row. */
internal fun SiteCard.toEntity() = SiteCardEntity(
    id = id,
    name = name,
    url = url,
    regionCode = region.code,
    iconKey = iconKey,
    adapterId = adapterId,
    alternateUrl = alternateUrl,
    alternateLabel = alternateLabel,
    sortOrder = sortOrder,
    enabled = enabled,
)
