package app.terndays.android.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.terndays.android.Prefs
import app.terndays.android.geo.Cities
import app.terndays.android.punch.PunchScheduler
import app.terndays.android.punch.PunchService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TernDaysTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.onboardingDone(this)) {
            PunchScheduler.scheduleNext(this)
            PunchService.maybeBackfill(this)
            Cities.reResolveHistoryIfNeeded(this) { changed ->
                runOnUiThread {
                    Toast.makeText(this, "城市库已更新，自动修正了 $changed 条历史记录", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val start = if (Prefs.onboardingDone(context)) "home" else "onboarding"

    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier.fillMaxSize().background(Td.Bg),
    ) {
        composable("onboarding") {
            OnboardingScreen(
                onDone = {
                    Prefs.setOnboardingDone(context)
                    PunchScheduler.scheduleNext(context)
                    PunchService.maybeBackfill(context)
                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                },
            )
        }
        composable("home") {
            HomeScreen(
                onOpenCity = { key, year -> nav.navigate("city/${Uri.encode(key)}/$year") },
                onExport = { year -> nav.navigate("export/$year") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "city/{key}/{year}",
            arguments = listOf(
                navArgument("key") { type = NavType.StringType },
                navArgument("year") { type = NavType.IntType },
            ),
        ) { entry ->
            CityDetailScreen(
                cityKey = Uri.decode(entry.arguments!!.getString("key")!!),
                year = entry.arguments!!.getInt("year"),
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "export/{year}",
            arguments = listOf(navArgument("year") { type = NavType.IntType }),
        ) { entry ->
            ExportScreen(
                initialYear = entry.arguments!!.getInt("year"),
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
