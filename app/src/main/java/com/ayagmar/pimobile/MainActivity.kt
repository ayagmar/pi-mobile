package com.ayagmar.pimobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.ayagmar.pimobile.di.AppGraph
import com.ayagmar.pimobile.perf.PerformanceMetrics
import com.ayagmar.pimobile.perf.PerformanceMetrics.recordAppStart
import com.ayagmar.pimobile.ui.piMobileApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appGraph: AppGraph by lazy {
        AppGraph(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Record app start as early as possible
        recordAppStart()

        super.onCreate(savedInstanceState)
        setContent {
            piMobileApp(appGraph = appGraph)
        }
    }

    override fun onResume() {
        super.onResume()
        // Log any pending metrics
        lifecycleScope.launch {
            val timings = PerformanceMetrics.flushTimings()
            timings.forEach { timing ->
                android.util.Log.d(
                    "PerfMetrics",
                    "Flushed: ${timing.metric} = ${timing.durationMs}ms",
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            lifecycleScope.launch {
                appGraph.sessionController.disconnect()
            }
        }
        super.onDestroy()
    }
}
