package com.rhvoice.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConnectionStatus {
    data object Stopped : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

object ConnectionStateHolder {
    private val _state = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Stopped)
    val state: StateFlow<ConnectionStatus> = _state.asStateFlow()

    internal fun set(value: ConnectionStatus) {
        _state.value = value
    }
}
