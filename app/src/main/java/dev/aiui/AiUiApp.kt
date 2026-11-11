package dev.aiui

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.aiui.data.AttachmentStore
import dev.aiui.data.ChatRepository
import dev.aiui.data.KeyVault
import dev.aiui.data.PrefsRepository
import dev.aiui.data.ProfileRepository
import dev.aiui.data.db.AppDatabase
import dev.aiui.llm.AnthropicAdapter
import dev.aiui.llm.ApiFormat
import dev.aiui.llm.OpenAiCompatAdapter
import dev.aiui.llm.buildHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = buildHttpClient()
    val db = AppDatabase.create(app)
    val prefs = PrefsRepository(app)
    val attachments = AttachmentStore(app)
    private val clients = mapOf(
        ApiFormat.ANTHROPIC to AnthropicAdapter(http),
        ApiFormat.OPENAI to OpenAiCompatAdapter(http),
    )
    val profiles = ProfileRepository(db, KeyVault(), clients)
    val chats = ChatRepository(db, profiles, prefs, attachments, clients, appScope)
}

class AiUiApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A reply that was streaming when the process died is kept, marked as interrupted.
        container.appScope.launch { container.chats.markInterruptedReplies() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .build()
}
