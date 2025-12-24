/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.subscription

import Logger
import Logger.LOG_IAB
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generic State Machine Framework inspired by:
 * https://blog.lawrencejones.dev/state-machines/
 *
 * This framework provides:
 * - Type-safe state transitions
 * - Guard conditions for transitions
 * - Side effects (actions) on transitions
 * - Event queuing and processing
 * - Comprehensive logging and debugging
 * - Thread-safe operations
 */

/**
 * Base interface for all states
 */
interface State {
    val name: String get() = this::class.java.simpleName
}

/**
 * Base interface for all events
 */
interface Event {
    val name: String get() = this::class.java.simpleName
}

/**
 * Represents a state transition
 */
data class Transition<S : State, E : Event>(
    val fromState: S,
    val event: E,
    val toState: S,
    val guard: suspend (E, Any?) -> Boolean = { _, _ -> true },
    val action: suspend (E, Any?) -> Unit = { _, _ -> }
)

/**
 * Represents a transition record for audit trail
 */
data class TransitionRecord<S : State, E : Event>(
    val fromState: S,
    val toState: S,
    val event: E?,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true,
    val error: String? = null
)

/**
 * Generic State Machine implementation
 */
abstract class StateMachine<S : State, E : Event, D : Any>(
    private val initialState: S,
    private val tag: String = "StateMachine"
) {

    private val _currentState = MutableStateFlow(initialState)
    private val _data = MutableStateFlow<D?>(null)
    private val _transitionHistory = MutableStateFlow<List<TransitionRecord<S, E>>>(emptyList())
    private val _lastError = MutableStateFlow<String?>(null)

    val currentState: StateFlow<S> = _currentState.asStateFlow()
    val data: StateFlow<D?> = _data.asStateFlow()
    val transitionHistory: StateFlow<List<TransitionRecord<S, E>>> = _transitionHistory.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }

    // Abstract methods to be implemented by subclasses
    abstract suspend fun getValidTransitions(): List<Transition<S, E>>
    abstract suspend fun onStateChanged(fromState: S, toState: S, event: E)
    abstract suspend fun onTransitionFailed(fromState: S, event: E, error: String)
    abstract suspend fun onInvalidTransition(fromState: S, event: E)

    /**
     * Process an event and potentially transition to a new state
     */
    suspend fun processEvent(event: E, newData: D? = null) {
        try {
            val currentState = _currentState.value
            val currentData = _data.value

            Logger.d(
                LOG_IAB,
                "$tag: Processing event: ${event.name} in state: ${currentState.name}"
            )

            val transition = findValidTransition(currentState, event)

            if (transition != null) {
                // Check guard condition
                if (transition.guard(event, currentData)) {
                    try {
                        // Update data if provided
                        newData?.let { _data.value = it }

                        // Execute action
                        transition.action(event, _data.value)

                        // Transition to new state
                        _currentState.value = transition.toState

                        // Record successful transition
                        recordTransition(
                            TransitionRecord(
                                fromState = currentState,
                                toState = transition.toState,
                                event = event,
                                success = true
                            )
                        )

                        // Clear any previous errors
                        _lastError.value = null
                        Logger.i(
                            LOG_IAB,
                            "$tag: State transition: ${currentState.name} -> ${transition.toState.name}"
                        )

                        // Notify subclass
                        onStateChanged(currentState, transition.toState, event)

                    } catch (e: Exception) {
                        val errorMessage = "Action failed during transition: ${e.message}"
                        Logger.e(LOG_IAB, "$tag: $errorMessage", e)

                        // Record failed transition
                        recordTransition(
                            TransitionRecord(
                                fromState = currentState,
                                toState = transition.toState,
                                event = event,
                                success = false,
                                error = errorMessage
                            )
                        )

                        _lastError.value = errorMessage
                        onTransitionFailed(currentState, event, errorMessage)
                    }
                } else {
                    Logger.w(
                        LOG_IAB,
                        "$tag: Guard condition failed for event: ${event.name} in state: ${currentState.name}"
                    )
                    onInvalidTransition(currentState, event)
                }
            } else {
                Logger.w(
                    LOG_IAB,
                    "$tag: No valid transition found for event: ${event.name} in state: ${currentState.name}"
                )
                onInvalidTransition(currentState, event)
            }

        } catch (e: Exception) {
            val errorMessage = "Error processing event ${event.name}: ${e.message}"
            Logger.e(LOG_IAB, "$tag: $errorMessage", e)
            _lastError.value = errorMessage
            onTransitionFailed(_currentState.value, event, errorMessage)
        }
    }

    /**
     * Find a valid transition for the current state and event
     */
    private suspend fun findValidTransition(currentState: S, event: E): Transition<S, E>? {
        val validTransitions = getValidTransitions()
        return validTransitions.find { transition ->
            transition.fromState::class.java == currentState::class.java &&
            transition.event::class.java == event::class.java
        }
    }

    /**
     * Record a transition in the history
     */
    private fun recordTransition(record: TransitionRecord<S, E>) {
        val currentHistory = _transitionHistory.value.toMutableList()
        currentHistory.add(record)

        // Keep only last MAX_HISTORY_SIZE transitions
        if (currentHistory.size > MAX_HISTORY_SIZE) {
            currentHistory.removeAt(0)
        }

        _transitionHistory.value = currentHistory
    }

    /**
     * Get current state
     */
    fun getCurrentState(): S = _currentState.value

    /**
     * Get current data
     */
    fun getCurrentData(): D? = _data.value

    /**
     * Update data without triggering a state transition
     */
    suspend fun updateData(newData: D) {
        _data.value = newData
        Logger.i(LOG_IAB, "$tag: Data updated to: $newData")
    }

    /**
     * Directly transition to a new state without processing events
     * This is useful for initialization from database where we know the exact target state
     */
    /*suspend fun directTransitionTo(newState: S) {
        val currentState = _currentState.value

        if (currentState != newState) {
            _currentState.value = newState

            // Record successful direct transition
            recordTransition(
                TransitionRecord(
                    fromState = currentState,
                    toState = newState,
                    event = null, // No event for direct transitions
                    success = true
                )
            )

            // Clear any previous errors
            _lastError.value = null

            Logger.i(
                LOG_IAB,
                "$tag: Direct state transition: ${currentState.name} -> ${newState.name}"
            )
        } else {
            Logger.d(LOG_IAB, "$tag: Already in state ${newState.name}, no transition needed")
        }
    }*/

    /**
     * Check if a transition is valid for the current state
     */
    suspend fun canTransition(event: E): Boolean {
        return findValidTransition(_currentState.value, event) != null
    }

    /**
     * Get all valid events for the current state
     */
    suspend fun getValidEvents(): List<E> {
        val validTransitions = getValidTransitions()
        return validTransitions.filter { it.fromState::class.java == _currentState.value::class.java }
            .map { it.event }
    }

    /**
     * Reset the state machine to initial state
     */
    suspend fun reset() {
        _currentState.value = initialState
        _data.value = null
        _lastError.value = null
        Logger.i(LOG_IAB, "$tag: State machine reset to initial state: ${initialState.name}")
    }

    /**
     * Get statistics about the state machine
     */
    fun getStatistics(): StateMachineStatistics {
        val history = _transitionHistory.value
        val totalTransitions = history.size
        val successfulTransitions = history.count { it.success }
        val failedTransitions = history.count { !it.success }
        val stateDistribution = history.groupBy { it.toState.name }.mapValues { it.value.size }
        val eventDistribution = history.groupBy { it.event?.name ?: "" }.mapValues { it.value.size }

        return StateMachineStatistics(
            totalTransitions = totalTransitions,
            successfulTransitions = successfulTransitions,
            failedTransitions = failedTransitions,
            successRate = if (totalTransitions > 0) successfulTransitions.toDouble() / totalTransitions else 0.0,
            stateDistribution = stateDistribution,
            eventDistribution = eventDistribution,
            currentState = _currentState.value.name,
            hasError = _lastError.value != null
        )
    }
}

/**
 * Statistics about state machine usage
 */
data class StateMachineStatistics(
    val totalTransitions: Int,
    val successfulTransitions: Int,
    val failedTransitions: Int,
    val successRate: Double,
    val stateDistribution: Map<String, Int>,
    val eventDistribution: Map<String, Int>,
    val currentState: String,
    val hasError: Boolean
)

/**
 * Builder for creating state machines with fluent API
 */
class StateMachineBuilder<S : State, E : Event, D : Any>(
    private val initialState: S,
    private val tag: String = "StateMachine"
) {

    private val transitions = mutableListOf<Transition<S, E>>()
    private var onStateChangedHandler: suspend (S, S, E) -> Unit = { _, _, _ -> }
    private var onTransitionFailedHandler: suspend (S, E, String) -> Unit = { _, _, _ -> }
    private var onInvalidTransitionHandler: suspend (S, E) -> Unit = { _, _ -> }

    fun addTransition(transition: Transition<S, E>): StateMachineBuilder<S, E, D> {
        transitions.add(transition)
        return this
    }

    fun addTransition(
        fromState: S,
        event: E,
        toState: S,
        guard: suspend (E, Any?) -> Boolean = { _, _ -> true },
        action: suspend (E, Any?) -> Unit = { _, _ -> }
    ): StateMachineBuilder<S, E, D> {
        return addTransition(Transition(fromState, event, toState, guard, action))
    }

    fun onStateChanged(handler: suspend (S, S, E) -> Unit): StateMachineBuilder<S, E, D> {
        onStateChangedHandler = handler
        return this
    }

    fun onTransitionFailed(handler: suspend (S, E, String) -> Unit): StateMachineBuilder<S, E, D> {
        onTransitionFailedHandler = handler
        return this
    }

    fun onInvalidTransition(handler: suspend (S, E) -> Unit): StateMachineBuilder<S, E, D> {
        onInvalidTransitionHandler = handler
        return this
    }

    fun build(): StateMachine<S, E, D> {
        return object : StateMachine<S, E, D>(initialState, tag) {
            override suspend fun getValidTransitions(): List<Transition<S, E>> = transitions
            override suspend fun onStateChanged(fromState: S, toState: S, event: E) = onStateChangedHandler(fromState, toState, event)
            override suspend fun onTransitionFailed(fromState: S, event: E, error: String) = onTransitionFailedHandler(fromState, event, error)
            override suspend fun onInvalidTransition(fromState: S, event: E) = onInvalidTransitionHandler(fromState, event)
        }
    }
}

/**
 * Extension functions for easier state machine creation
 */
fun <S : State, E : Event, D : Any> createStateMachine(
    initialState: S,
    tag: String = "StateMachine",
    builder: StateMachineBuilder<S, E, D>.() -> Unit
): StateMachine<S, E, D> {
    return StateMachineBuilder<S, E, D>(initialState, tag).apply(builder).build()
}

/**
 * DSL for creating transitions
 */
infix fun <S : State, E : Event> S.on(event: E): TransitionBuilder<S, E> {
    return TransitionBuilder(this, event)
}

class TransitionBuilder<S : State, E : Event>(
    private val fromState: S,
    private val event: E
) {
    infix fun goTo(toState: S): TransitionBuilderWithState<S, E> {
        return TransitionBuilderWithState(fromState, event, toState)
    }
}

class TransitionBuilderWithState<S : State, E : Event>(
    private val fromState: S,
    private val event: E,
    private val toState: S
) {
    infix fun guardedBy(guard: suspend (E, Any?) -> Boolean): TransitionBuilderWithGuard<S, E> {
        return TransitionBuilderWithGuard(fromState, event, toState, guard)
    }

    infix fun withAction(action: suspend (E, Any?) -> Unit): Transition<S, E> {
        return Transition(fromState, event, toState, action = action)
    }

    fun build(): Transition<S, E> {
        return Transition(fromState, event, toState)
    }
}

class TransitionBuilderWithGuard<S : State, E : Event>(
    private val fromState: S,
    private val event: E,
    private val toState: S,
    private val guard: suspend (E, Any?) -> Boolean
) {
    infix fun withAction(action: suspend (E, Any?) -> Unit): Transition<S, E> {
        return Transition(fromState, event, toState, guard, action)
    }

    fun build(): Transition<S, E> {
        return Transition(fromState, event, toState, guard)
    }
}
