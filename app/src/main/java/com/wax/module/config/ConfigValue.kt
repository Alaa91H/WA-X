package com.wax.module.config

/**
 * A configuration value in a form that can be written to [android.content.SharedPreferences].
 *
 * Modelled explicitly rather than as `Any` so that decoding a backup file is a total
 * function: a document either decodes into entries the preferences editor accepts, or it
 * is rejected. That is what makes an all-or-nothing restore possible.
 */
sealed interface ConfigValue {
    data class Text(
        val value: String,
    ) : ConfigValue

    data class Flag(
        val value: Boolean,
    ) : ConfigValue

    data class Whole(
        val value: Int,
    ) : ConfigValue

    data class Wide(
        val value: Long,
    ) : ConfigValue

    data class Decimal(
        val value: Float,
    ) : ConfigValue

    data class Texts(
        val value: Set<String>,
    ) : ConfigValue
}
