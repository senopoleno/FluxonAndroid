package io.github.p1neapplexpress.openflux.event

sealed interface AppEvent {
    data class LogMessage(val message: String) : AppEvent
    data class ToggleTunnel(val id: Long, val enabled: Boolean) : AppEvent
    data class SpeedUpdate(val rxSpeed: Long, val txSpeed: Long) : AppEvent
    data object TransportConnected : AppEvent
    data object TransportDisconnected : AppEvent
}
