package io.reyaak.router.time

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Wall-clock epoch milliseconds.
 *
 * One place rather than an expect/actual per target: `kotlin.time.Clock` is
 * common stdlib, so the router needs no platform source set to know the time.
 * Everything that reads the clock takes it as a constructor parameter defaulting
 * to this, which is also what makes cooldowns and rate windows testable.
 */
@OptIn(ExperimentalTime::class)
fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
