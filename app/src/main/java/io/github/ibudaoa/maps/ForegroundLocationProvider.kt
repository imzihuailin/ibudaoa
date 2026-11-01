package io.github.ibudaoa.maps

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class ForegroundLocationSnapshot(val fix:DisplayFix?=null,val diagnostics:LocationDiagnostics?=null,
    val message:String="正在获取前台位置",val retry:()->Unit={})
internal val LocalForegroundLocation=staticCompositionLocalOf { ForegroundLocationSnapshot() }

/** Shared by all maps and dialogs. Page disposal cannot stop or clear the Activity's location. */
@Composable
internal fun ForegroundLocationProvider(enabled:Boolean,permissionRevision:Int,onFix:(DisplayFix?)->Unit,
    onDiagnostics:(LocationDiagnostics)->Unit,content:@Composable ()->Unit) {
    val context=LocalContext.current.applicationContext
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestFix by rememberUpdatedState(onFix)
    val latestDiagnostics by rememberUpdatedState(onDiagnostics)
    val locator=remember(context) { ForegroundAmapLocation(context) }
    var snapshot by remember { mutableStateOf(ForegroundLocationSnapshot()) }
    DisposableEffect(locator,lifecycle,enabled,permissionRevision) {
        fun stop() {
            locator.stop();snapshot=ForegroundLocationSnapshot();latestFix(null)
        }
        fun start() {
            if(!enabled) { stop();return }
            locator.start(onFix={ fix,message ->
                snapshot=snapshot.copy(fix=fix,message=message);latestFix(fix)
            },onDiagnostics={ diagnostics ->
                snapshot=snapshot.copy(diagnostics=diagnostics);latestDiagnostics(diagnostics)
            })
        }
        val observer=LifecycleEventObserver { _,event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE,Lifecycle.Event.ON_STOP,Lifecycle.Event.ON_DESTROY -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))start() else stop()
        onDispose { lifecycle.removeObserver(observer);stop() }
    }
    CompositionLocalProvider(LocalForegroundLocation provides snapshot.copy(retry=locator::retry),content=content)
}
