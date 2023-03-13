/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.completeWith
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.HasProject
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginLifecycle
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginLifecycle.IllegalLifecycleException
import org.jetbrains.kotlin.gradle.plugin.kotlinPluginLifecycle
import org.jetbrains.kotlin.tooling.core.ExtrasLazyProperty
import org.jetbrains.kotlin.tooling.core.HasMutableExtras
import org.jetbrains.kotlin.tooling.core.extrasLazyProperty
import java.io.Serializable
import kotlin.reflect.KProperty

/**
 * See [KotlinPluginLifecycle]:
 * This [Future] represents a value that will be available in some 'future' time.
 *
 *
 * #### Simple use case example: Deferring the value of a property to a given [KotlinPluginLifecycle.Stage]:
 *
 * ```kotlin
 * val myFutureProperty: Future<Int> = project.future {
 *     await(FinaliseDsl) // <- suspends
 *     42
 * }
 * ```
 *
 * Futures can also be used to dynamically extend entities implementing [HasMutableExtras] and [HasProject]
 * #### Example Usage: Extending KotlinSourceSet with a property that relies on the final refines edges.
 *
 * ```kotlin
 * internal val KotlinSourceSet.dependsOnCommonMain: Future<Boolean> by lazyFuture("dependsOnCommonMain") {
 *    await(AfterFinaliseRefinesEdges)
 *    return dependsOn.contains { it.name == "commonMain") }
 * }
 * ```
 */
internal interface Future<T> {
    suspend fun await(): T
    fun getOrThrow(): T
}

internal interface CompletableFuture<T> : Future<T> {
    fun complete(value: T)
}

internal fun CompletableFuture<Unit>.complete() = complete(Unit)

internal inline fun <Receiver, reified T> lazyFuture(
    name: String? = null, noinline block: suspend Receiver.() -> T
): ExtrasLazyProperty<Receiver, Future<T>> where Receiver : HasMutableExtras, Receiver : HasProject {
    return extrasLazyProperty<Receiver, Future<T>>(name) {
        project.future { block() }
    }
}

internal fun <T> Project.future(block: suspend Project.() -> T): Future<T> = kotlinPluginLifecycle.future { block() }

internal fun <T> KotlinPluginLifecycle.future(block: suspend () -> T): Future<T> {
    return FutureImpl<T>(CompletableDeferred()).also { future ->
        launch { future.completeWith(runCatching { block() }) }
    }
}

internal fun <T> CompletableFuture(): CompletableFuture<T> {
    return FutureImpl()
}

@OptIn(ExperimentalCoroutinesApi::class)
private class FutureImpl<T>(private val deferred: CompletableDeferred<T> = CompletableDeferred()) : CompletableFuture<T>, Serializable {
    fun completeWith(result: Result<T>) = deferred.completeWith(result)

    override fun complete(value: T) {
        deferred.complete(value)
    }

    override suspend fun await(): T {
        return deferred.await()
    }

    override fun getOrThrow(): T {
        return if (deferred.isCompleted) deferred.getCompleted() else throw IllegalLifecycleException(
            "Future was not completed yet"
        )
    }
}
