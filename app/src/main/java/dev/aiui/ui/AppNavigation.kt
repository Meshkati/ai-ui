package dev.aiui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.aiui.AppContainer
import dev.aiui.ui.chat.ChatScreen
import dev.aiui.ui.chat.ChatViewModel
import dev.aiui.ui.personalization.PersonalizationScreen
import dev.aiui.ui.profiles.ProfileEditScreen
import dev.aiui.ui.profiles.ProfileEditViewModel
import dev.aiui.ui.settings.SettingsScreen
import dev.aiui.ui.settings.SettingsViewModel
import dev.aiui.ui.theme.AccentTheme

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PERSONALIZATION = "personalization"
    const val PROFILE = "profile/{id}"
    const val NEW_PROFILE_ID = -1L

    fun profile(id: Long?) = "profile/${id ?: NEW_PROFILE_ID}"
}

@Composable
fun AppNavigation(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            val vm: ChatViewModel = viewModel(factory = viewModelFactory { initializer { ChatViewModel(container) } })
            val state by vm.state.collectAsStateWithLifecycle()
            // The chat takes the colours of the profile it is talking to.
            AccentTheme(state.selection?.accentColor) {
                ChatScreen(
                    vm = vm,
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onAddProfile = { nav.navigate(Routes.profile(null)) },
                    onEditProfile = { nav.navigate(Routes.profile(it)) },
                )
            }
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenProfile = { nav.navigate(Routes.profile(it)) },
                onOpenPersonalization = { nav.navigate(Routes.PERSONALIZATION) },
            )
        }
        composable(Routes.PERSONALIZATION) {
            PersonalizationScreen(prefsRepository = container.prefs, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.PROFILE,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id")?.takeIf { it != Routes.NEW_PROFILE_ID }
            val vm: ProfileEditViewModel = viewModel(
                key = "profile-$id",
                factory = viewModelFactory { initializer { ProfileEditViewModel(container, id) } },
            )
            ProfileEditScreen(vm = vm, onDone = { nav.popBackStack() })
        }
    }
}
