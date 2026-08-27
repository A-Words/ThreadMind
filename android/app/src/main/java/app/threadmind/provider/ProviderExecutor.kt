package app.threadmind.provider

import app.threadmind.domain.ConfirmedActionSnapshot

sealed interface ProviderResult {
    data class Succeeded(val targetRecordId: String) : ProviderResult
    data class Failed(val code: String, val message: String) : ProviderResult
}
/** The only external-write boundary. Callers cannot pass a draft ActionCard. */
interface ProviderExecutor {
    suspend fun execute(snapshot: ConfirmedActionSnapshot): ProviderResult
}
