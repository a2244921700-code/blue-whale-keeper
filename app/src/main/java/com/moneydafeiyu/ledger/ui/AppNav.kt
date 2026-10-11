package com.moneydafeiyu.ledger.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.moneydafeiyu.ledger.ui.add.AddScreen
import com.moneydafeiyu.ledger.ui.callguard.CallGuardScreen
import com.moneydafeiyu.ledger.ui.component.AppBackground
import com.moneydafeiyu.ledger.ui.evidence.EvidenceScreen
import com.moneydafeiyu.ledger.ui.fortune.FortuneScreen
import com.moneydafeiyu.ledger.ui.home.HomeScreen
import com.moneydafeiyu.ledger.ui.parser.ParserTestScreen
import com.moneydafeiyu.ledger.ui.permission.PermissionsScreen
import com.moneydafeiyu.ledger.ui.rules.RulesScreen
import com.moneydafeiyu.ledger.ui.settings.SettingsScreen
import com.moneydafeiyu.ledger.ui.stats.CategoryStatsScreen
import com.moneydafeiyu.ledger.ui.splash.SplashScreen

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val ADD = "add"
    const val FORTUNE = "fortune"
    const val EVIDENCE = "evidence"
    const val PERMISSIONS = "permissions"
    const val SETTINGS = "settings"
    const val RULES = "rules"
    const val PARSER_TEST = "parser_test"
    const val CALL_GUARD = "call_guard"
    const val STATS = "stats"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen { nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } }
        }
        composable(Routes.HOME) {
            // 背景铺在导航层：页面本身不用知道自己有没有背景，
            // Scaffold 的底色是透明的，图自然透出来
            AppBackground("bg_main.webp") {
                HomeScreen(
                    onAdd = { nav.navigate(Routes.ADD) },
                    onFortune = { nav.navigate(Routes.FORTUNE) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onStats = { nav.navigate(Routes.STATS) },
                )
            }
        }
        composable(Routes.ADD) { AddScreen(onDone = { nav.popBackStack() }) }
        composable(Routes.FORTUNE) {
            // 这张素材本身只有 196px 宽，1:1 显示会小得像个图标，
            // 所以单独放宽到 3 倍（再大就糊了）
            AppBackground("bg_fortune.webp", maxScale = 3f) {
                FortuneScreen(onBack = { nav.popBackStack() })
            }
        }
        composable(Routes.EVIDENCE) {
            EvidenceScreen(
                onBack = { nav.popBackStack() },
                onOpenParserTest = { nav.navigate(Routes.PARSER_TEST) },
            )
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onBack = { nav.popBackStack() },
                onOpenEvidence = { nav.navigate(Routes.EVIDENCE) },
            )
        }
        composable(Routes.SETTINGS) {
            AppBackground("bg_settings.webp") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onRules = { nav.navigate(Routes.RULES) },
                onPermissions = { nav.navigate(Routes.PERMISSIONS) },
                onEvidence = { nav.navigate(Routes.EVIDENCE) },
                onCallGuard = { nav.navigate(Routes.CALL_GUARD) },
            )
            }
        }
        composable(Routes.RULES) { RulesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PARSER_TEST) { ParserTestScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CALL_GUARD) { CallGuardScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.STATS) {
            AppBackground("bg_main.webp") {
                CategoryStatsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
