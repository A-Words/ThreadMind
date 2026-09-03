package app.threadmind.provider

import app.threadmind.domain.ConfirmedActionSnapshot
import app.threadmind.domain.ContactContextSnapshot

sealed interface ProviderResult {
    data class Succeeded(val targetRecordId: String, val contactContext: ContactContextSnapshot? = null) : ProviderResult
    data class Failed(val code: String, val message: String) : ProviderResult
}
/** The only external-write boundary. Callers cannot pass a draft ActionCard. */
interface ProviderExecutor : ProviderInspector {
    suspend fun execute(snapshot: ConfirmedActionSnapshot): ProviderResult
}
