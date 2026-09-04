package io.reyaak.ui

import io.reyaak.router.score.RoutingStrategy

/**
 * The strategy vocabulary, in one place.
 *
 * The Router screen names them in chips and the chat composer names the active
 * one in its chip, so a label defined next to only one of those two would drift
 * the moment either is edited.
 */
fun strategyLabel(strategy: RoutingStrategy): String = when (strategy) {
    RoutingStrategy.BALANCED -> "Balanced"
    RoutingStrategy.FASTEST -> "Fastest"
    RoutingStrategy.SMARTEST -> "Smartest"
    RoutingStrategy.RELIABLE -> "Reliable"
    RoutingStrategy.PRIORITY -> "Manual order"
    RoutingStrategy.CUSTOM -> "Custom"
}

fun strategyDescription(strategy: RoutingStrategy): String = when (strategy) {
    RoutingStrategy.BALANCED -> "Reliability leads; speed and intelligence split the rest."
    RoutingStrategy.FASTEST -> "Prefers throughput, but a fast broken model still loses."
    RoutingStrategy.SMARTEST -> "Prefers capability, with reliability keeping it honest."
    RoutingStrategy.RELIABLE -> "Whatever is most likely to just work."
    RoutingStrategy.PRIORITY -> "Follows your explicit order and skips scoring."
    RoutingStrategy.CUSTOM -> "Custom weights from an imported config."
}

/** The chips the Router screen offers. CUSTOM is reachable only by import. */
val SelectableStrategies = listOf(
    RoutingStrategy.BALANCED,
    RoutingStrategy.FASTEST,
    RoutingStrategy.SMARTEST,
    RoutingStrategy.RELIABLE,
    RoutingStrategy.PRIORITY,
)
