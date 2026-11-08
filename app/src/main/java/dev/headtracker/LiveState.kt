package dev.headtracker

/** Lock-free snapshot of what the service is doing, polled by the UI only while it is visible. */
object LiveState {
    @Volatile var running = false
    @Volatile var active = false
    @Volatile var yaw = 0f
    @Volatile var faceVisible = false
    @Volatile var fps = 0f
    @Volatile var status = ""
    @Volatile var sessions = ""
}
