package com.analytic.atribution.gb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object Locator {
    private val waiters = mutableMapOf<Class<*>, CompletableDeferred<Any>>()
    private val lock = Mutex()

    private suspend fun <T : Class<*>> T.getDeferred() = lock.withLock {
        waiters.getOrPut(this) { CompletableDeferred() }
    }

    suspend fun <T : Any> register(instance: T) {
        instance::class.java.let {
            if (!it.getDeferred().complete(instance)) {
                throw RuntimeException("${it.name} already resolved")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> resolve(clazz: Class<T>): T {
        return clazz.getDeferred().await() as T
    }

    suspend inline fun <reified T : Any> resolve(): T = resolve(T::class.java)
}