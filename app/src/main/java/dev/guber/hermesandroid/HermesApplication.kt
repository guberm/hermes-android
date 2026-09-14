package dev.guber.hermesandroid

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.guber.hermesandroid.ui.HermesViewModel

/** Keep the connection alive when the activity closes while a response is pending. */
class HermesApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val viewModel: HermesViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[HermesViewModel::class.java]
    }
}
