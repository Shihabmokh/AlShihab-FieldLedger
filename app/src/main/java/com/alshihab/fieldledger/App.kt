package com.alshihab.fieldledger

import android.app.Application
import android.content.Context
import com.alshihab.fieldledger.data.LedgerDb
import com.alshihab.fieldledger.data.Repo
import com.alshihab.fieldledger.security.TimeGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object Graph {
    lateinit var appContext: Context
        private set
    lateinit var repo: Repo
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        repo = Repo(LedgerDb.build(appContext), appContext)
        TimeGuard.onRollback = { now, last ->
            scope.launch { repo.logEvent("تنبيه أمني", repo.clockIncident(now, last)) }
        }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        Graph.init(this)
        com.alshihab.fieldledger.security.AttachmentStore.cleanCache(this)
        // seal statements whose 24h window has closed (tamper-evident hash chain)
        Graph.scope.launch { runCatching { Graph.repo.sealLockedSheets() } }
    }
}
