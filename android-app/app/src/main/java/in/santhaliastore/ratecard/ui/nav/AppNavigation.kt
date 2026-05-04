package `in`.santhaliastore.ratecard.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import `in`.santhaliastore.ratecard.ui.screens.add_entry.AddEditEntryScreen
import `in`.santhaliastore.ratecard.ui.screens.add_item.AddEditItemScreen
import `in`.santhaliastore.ratecard.ui.screens.home.HomeScreen
import `in`.santhaliastore.ratecard.ui.screens.item_detail.ItemDetailScreen
import `in`.santhaliastore.ratecard.ui.screens.settings.SettingsScreen

/**
 * Tiny route registry. We keep the literal route strings inside this
 * file so screens never have to know about each other's routes —
 * navigation goes through the helper builders below.
 */
object Routes {
    const val Home = "home"
    const val Settings = "settings"

    private const val ITEM_CODE_ARG = "code"
    const val ItemDetailPattern = "item/{$ITEM_CODE_ARG}"
    fun itemDetail(code: String) = "item/${encode(code)}"
    fun itemDetailArg() = ITEM_CODE_ARG

    const val AddItem = "items/add"

    private const val EDIT_CODE_ARG = "code"
    const val EditItemPattern = "items/edit/{$EDIT_CODE_ARG}"
    fun editItem(code: String) = "items/edit/${encode(code)}"
    fun editItemArg() = EDIT_CODE_ARG

    private const val ADD_ENTRY_CODE_ARG = "itemCode"
    const val AddEntryPattern = "entries/add/{$ADD_ENTRY_CODE_ARG}"
    fun addEntry(itemCode: String) = "entries/add/${encode(itemCode)}"
    fun addEntryArg() = ADD_ENTRY_CODE_ARG

    private const val EDIT_ENTRY_ID_ARG = "entryId"
    const val EditEntryPattern = "entries/edit/{$EDIT_ENTRY_ID_ARG}"
    fun editEntry(entryId: String) = "entries/edit/${encode(entryId)}"
    fun editEntryArg() = EDIT_ENTRY_ID_ARG

    /**
     * Item codes are typed by the user and may contain unusual
     * characters. We URL-encode before stuffing them into a path so
     * "/" or "%" never confuse the router.
     */
    private fun encode(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8")
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.Home
    ) {
        composable(Routes.Home) {
            HomeScreen(
                onItemClick = { code -> navController.navigate(Routes.itemDetail(code)) },
                onAddItem = { navController.navigate(Routes.AddItem) },
                onOpenSettings = { navController.navigate(Routes.Settings) }
            )
        }

        composable(Routes.AddItem) {
            AddEditItemScreen(
                editingCode = null,
                // Add flow: there's no Item Detail upstream to redirect
                // to, so just unwind to Home. The savedCode is unused.
                onDone = { _ -> navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.EditItemPattern,
            arguments = listOf(navArgument(Routes.editItemArg()) { type = NavType.StringType })
        ) { backStackEntry ->
            val code = backStackEntry.arguments?.getString(Routes.editItemArg())
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                .orEmpty()
            AddEditItemScreen(
                editingCode = code,
                // Edit flow: the user came from an Item Detail anchored
                // to `code`. If they renamed the item the saved code
                // differs, and the Item Detail behind us is now stale
                // (its route arg is the old, now-tombstoned code).
                //
                // Pop both this Edit screen AND the stale Item Detail
                // off the back stack, then push a fresh Item Detail at
                // the new code. The user lands on a screen that
                // actually points at their renamed row.
                //
                // If the code is unchanged (e.g. user only edited the
                // name), a plain pop is enough — the Item Detail
                // upstream re-observes its row reactively.
                onDone = { savedCode ->
                    if (savedCode.isNotEmpty() && savedCode != code) {
                        navController.navigate(Routes.itemDetail(savedCode)) {
                            // Drop everything up to and including the
                            // stale Item Detail so back-press from the
                            // new detail goes to Home, not to a dead
                            // detail page.
                            //
                            // popUpTo matches against destination route
                            // *patterns*, not substituted paths — so we
                            // pop by `ItemDetailPattern`. There is only
                            // ever one Item Detail in the back stack at
                            // this point (this Edit screen is its
                            // direct child) so popping the pattern is
                            // exactly the stale entry we want to drop.
                            popUpTo(Routes.ItemDetailPattern) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.ItemDetailPattern,
            arguments = listOf(navArgument(Routes.itemDetailArg()) { type = NavType.StringType })
        ) { backStackEntry ->
            val code = backStackEntry.arguments?.getString(Routes.itemDetailArg())
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                .orEmpty()
            ItemDetailScreen(
                itemCode = code,
                onBack = { navController.popBackStack() },
                onEditItem = { navController.navigate(Routes.editItem(code)) },
                onAddEntry = { navController.navigate(Routes.addEntry(code)) },
                onEditEntry = { entryId -> navController.navigate(Routes.editEntry(entryId)) },
                onDeletedItem = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.AddEntryPattern,
            arguments = listOf(navArgument(Routes.addEntryArg()) { type = NavType.StringType })
        ) { backStackEntry ->
            val itemCode = backStackEntry.arguments?.getString(Routes.addEntryArg())
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                .orEmpty()
            AddEditEntryScreen(
                itemCode = itemCode,
                editingEntryId = null,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.EditEntryPattern,
            arguments = listOf(navArgument(Routes.editEntryArg()) { type = NavType.StringType })
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getString(Routes.editEntryArg())
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                .orEmpty()
            AddEditEntryScreen(
                itemCode = "", // resolved by VM from entry id
                editingEntryId = entryId,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
