package com.alshihab.fieldledger.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val PROJECTS = "projects"
    const val WORKERS = "workers"
    const val SETTINGS = "settings"
    const val AUDIT = "audit"
    const val CURRENCIES = "currencies"
    fun projectForm(id: Long) = "project_form/$id"
    fun workerForm(id: Long) = "worker_form/$id"
    fun project(id: Long) = "project/$id"
    fun assign(projectId: Long, assignmentId: Long) = "assign/$projectId/$assignmentId"
    fun attendance(projectId: Long, date: String) = "attendance/$projectId/$date"
    fun cashForm(projectId: Long, date: String, entryId: Long, kind: String) = "cash_form/$projectId/$date/$entryId/$kind"
    fun statement(assignmentId: Long) = "statement/$assignmentId"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val longArg = { name: String -> navArgument(name) { type = NavType.LongType } }
    val strArg = { name: String -> navArgument(name) { type = NavType.StringType } }

    NavHost(navController = nav, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) { ProjectsScreen(nav) }
        composable(Routes.WORKERS) { WorkersScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.AUDIT) { AuditScreen(nav) }
        composable(Routes.CURRENCIES) { CurrenciesScreen(nav) }
        composable("project_form/{id}", listOf(longArg("id"))) {
            ProjectFormScreen(nav, it.arguments?.getLong("id") ?: 0L)
        }
        composable("worker_form/{id}", listOf(longArg("id"))) {
            WorkerFormScreen(nav, it.arguments?.getLong("id") ?: 0L)
        }
        composable("project/{id}", listOf(longArg("id"))) {
            ProjectHome(nav, it.arguments?.getLong("id") ?: 0L)
        }
        composable("assign/{pid}/{aid}", listOf(longArg("pid"), longArg("aid"))) {
            AssignmentFormScreen(nav, it.arguments?.getLong("pid") ?: 0L, it.arguments?.getLong("aid") ?: 0L)
        }
        composable("attendance/{pid}/{date}", listOf(longArg("pid"), strArg("date"))) {
            DayEntryScreen(nav, it.arguments?.getLong("pid") ?: 0L, it.arguments?.getString("date") ?: "")
        }
        composable(
            "cash_form/{pid}/{date}/{eid}/{kind}",
            listOf(longArg("pid"), strArg("date"), longArg("eid"), strArg("kind"))
        ) {
            CashFormScreen(
                nav,
                it.arguments?.getLong("pid") ?: 0L,
                it.arguments?.getString("date") ?: "",
                it.arguments?.getLong("eid") ?: 0L,
                it.arguments?.getString("kind") ?: "OUT"
            )
        }
        composable("statement/{aid}", listOf(longArg("aid"))) {
            WorkerStatementScreen(nav, it.arguments?.getLong("aid") ?: 0L)
        }
    }
}
