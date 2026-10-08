package com.wax.module.activation

/**
 * What the status implies the user should do.
 *
 * The action is part of the resolved status rather than something the card picks afterwards,
 * for the same reason the failure code is: the mapping from state to advice is where a
 * resolver failure turns into "check the LSPosed scope", and that mapping has to be one
 * tested table rather than a set of `when` branches spread across a layout.
 */
enum class ActivationAction {
    /** Nothing is needed; this is the healthy case. */
    NONE,

    /** The app is not installed. */
    INSTALL_TARGET,

    /** The app is installed but has not been started. */
    OPEN_TARGET,

    /** A framework loaded WA X somewhere, but not into this process. */
    ENABLE_IN_FRAMEWORK,

    /** WA X is executing and is still starting; re-check in a moment. */
    WAIT,

    /** Read the failure code; the detail is in the runtime health record. */
    OPEN_DIAGNOSTICS,
}
