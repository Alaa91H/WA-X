package com.wax.module.xposed.bridge.client

import com.wax.module.xposed.bridge.WaeIIFace

abstract class BaseClient {
    abstract val service: WaeIIFace?

    abstract suspend fun connect(): Boolean

    abstract fun tryReconnect()
}
