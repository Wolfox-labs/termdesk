package dev.termdesk.app.data

/** A model entry declared in Codex's model catalog (models.json). */
data class CodexModel(
    val slug: String,
    val displayName: String,
    val contextWindow: Long?,
    val maxContextWindow: Long?,
    val defaultReasoning: String?,
    val reasoningLevels: List<String>,
    val vision: Boolean,
)

/** Current Codex configuration as reported by the PC agent. */
data class CodexConfig(
    val configPath: String,
    val modelsPath: String,
    val exists: Boolean,
    val model: String?,
    val modelProvider: String?,
    val reasoningEffort: String?,
    val providers: List<CodexProvider>,
    val models: List<CodexModel>,
    val enabledReasoningEfforts: List<String>,
    val backups: List<String>,
    val modelsError: String?,
)

data class CodexProvider(
    val id: String,
    val name: String?,
    val baseUrl: String?,
    val wireApi: String?,
    /** True when a token is stored. The token itself is never sent to the phone. */
    val hasToken: Boolean,
)

/** A provider preset the agent can apply. */
data class CodexProviderTemplate(
    val id: String,
    val name: String,
    val baseUrl: String,
    val wireApi: String,
    val models: List<String>,
    val contextWindow: Long,
    val reasoningLevels: List<String>,
    val defaultReasoning: String,
    val keyPrefix: String,
)
