package ai.rizzboard

import android.content.Context
import ai.rizzboard.ai.OpenAiCompatibleProvider
import ai.rizzboard.data.ProviderStore

object AppGraph {
    @Volatile private var storeRef: ProviderStore? = null
    val ai = OpenAiCompatibleProvider()
    fun store(context: Context): ProviderStore = storeRef ?: synchronized(this) {
        storeRef ?: ProviderStore(context.applicationContext).also { storeRef = it }
    }
}
