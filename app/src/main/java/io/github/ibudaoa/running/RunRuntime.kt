package io.github.ibudaoa.running

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import io.github.ibudaoa.AppViewModel

/** One application-owned coordinator shared by activities and the foreground service. */
object RunRuntime : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    @Synchronized fun model(application: Application): AppViewModel =
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[AppViewModel::class.java]
}
