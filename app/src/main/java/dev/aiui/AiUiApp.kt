package dev.aiui

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.aiui.audio.SpeechPlayer
import dev.aiui.audio.VoiceRecorder
import dev.aiui.data.AttachmentStore
import dev.aiui.data.ChatRepository
import dev.aiui.data.KeyVault
import dev.aiui.data.MemoryRepository
import dev.aiui.data.PrefsRepository
import dev.aiui.data.ProfileRepository
import dev.aiui.data.SpeechRepository
import dev.aiui.data.db.AppDatabase
import dev.aiui.llm.AnthropicAdapter
import dev.aiui.llm.ApiFormat
import dev.aiui.llm.OpenAiCompatAdapter
import dev.aiui.llm.buildHttpClient
import dev.aiui.update.GitHubReleases
import dev.aiui.update.UpdateChecker
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
    private val openAi = OpenAiCompatAdapter(http)
    private val clients = mapOf(
        ApiFormat.ANTHROPIC to AnthropicAdapter(http),
        ApiFormat.OPENAI to openAi,
    )
    val profiles = ProfileRepository(db, KeyVault(), clients)
    val memory = MemoryRepository(db.memories())
    val chats = ChatRepository(db, profiles, prefs, attachments, memory, clients, openAi, appScope)
    val speech = SpeechRepository(profiles, prefs, openAi)
    val voiceRecorder = VoiceRecorder(app)
    val speechPlayer = SpeechPlayer(app)
    val updates = UpdateChecker(prefs, GitHubReleases(http), BuildConfig.VERSION_NAME)
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
