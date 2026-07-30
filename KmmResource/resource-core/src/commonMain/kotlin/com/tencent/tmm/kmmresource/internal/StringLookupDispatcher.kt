package com.tencent.tmm.kmmresource.internal

/**
 * Keeps raw string lookup separate from platform formatting APIs.
 *
 * Some platform formatters are variadic. Calling one without arguments for a
 * template such as `%1$s` is undefined and can crash before Kotlin gets an
 * error result. Empty argument lists therefore always use [rawLookup].
 */
internal class StringLookupDispatcher(
    private val rawLookup: (resourceName: String) -> String?,
    private val formattedLookup: (
        resourceName: String,
        arguments: Array<out Any>
    ) -> String?
) {
    fun getString(resourceName: String, vararg arguments: Any): String? =
        if (arguments.isEmpty()) {
            rawLookup(resourceName)
        } else {
            formattedLookup(resourceName, arguments)
        }
}
