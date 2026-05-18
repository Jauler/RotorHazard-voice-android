package com.rhvoice.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.rhvoice.service.ConnectionStateHolder
import com.rhvoice.service.ConnectionStatus
import com.rhvoice.service.SocketService
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<ConnectionStatus> = ConnectionStateHolder.state

    fun start() {
        SocketService.start(getApplication())
    }

    fun stop() {
        SocketService.stop(getApplication())
    }
}
