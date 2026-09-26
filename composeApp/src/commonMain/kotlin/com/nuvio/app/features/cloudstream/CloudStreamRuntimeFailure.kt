package com.nuvio.app.features.cloudstream

/**
 * Turns a raw JVM/ART failure from inside a loaded extension into a statement
 * about *which host capability is missing*.
 *
 * ## Why
 *
 * When a `.cs3` asks for a class the host build does not expose, ART reports
 * `java.lang.NoClassDefFoundError: Failed resolution of: Lkotlin/collections/SetsKt;`.
 * Surfaced verbatim that is indistinguishable from a provider bug, and it was
 * reported as one. It is not: it means the host APK no longer contains a symbol
 * that dynamically loaded code resolves by name — almost always because R8
 * shrank or renamed it, occasionally because a dependency is not packaged.
 *
 * Classifying it makes the difference actionable, and keeps the rule in
 * [Kind.MISSING_CLASS] honest: a host ABI gap is *our* defect, not the
 * extension's.
 *
 * Deliberately pure and string-based so the whole decision table is unit
 * testable in common code with no Android runtime involved.
 */
internal object CloudStreamRuntimeFailure {

    enum class Kind {
        /** A class the extension resolves by name is absent from this build. */
        MISSING_CLASS,

        /** The class exists but a method/field the extension needs does not. */
        MISSING_MEMBER,

        /** Class loading/verification failed for some other structural reason. */
        LINKAGE,

        /** The provider itself failed (network, parsing, no match, ...). */
        PROVIDER,
    }

    data class Diagnosis(
        val kind: Kind,
        /** Binary name of the missing symbol, when one could be identified. */
        val symbol: String?,
        /** Text safe to show a user and to put in a bug report. */
        val message: String,
    ) {
        /** True when the fault lies in the host build rather than the extension. */
        val isHostRuntimeGap: Boolean
            get() = kind == Kind.MISSING_CLASS ||
                kind == Kind.MISSING_MEMBER ||
                kind == Kind.LINKAGE
    }

    private val MISSING_CLASS_TYPES = setOf(
        "NoClassDefFoundError",
        "ClassNotFoundException",
    )

    private val MISSING_MEMBER_TYPES = setOf(
        "NoSuchMethodError",
        "NoSuchMethodException",
        "NoSuchFieldError",
        "NoSuchFieldException",
    )

    private val LINKAGE_TYPES = setOf(
        "ExceptionInInitializerError",
        "IncompatibleClassChangeError",
        "AbstractMethodError",
        "IllegalAccessError",
        "VerifyError",
        "UnsatisfiedLinkError",
        "LinkageError",
    )

    /**
     * Classifies one failure.
     *
     * @param throwableTypeName simple class name of the throwable
     * @param rawMessage its message, if any
     * @param providerName provider the failure came from, for the text
     */
    fun describe(
        throwableTypeName: String,
        rawMessage: String?,
        providerName: String? = null,
    ): Diagnosis {
        val type = throwableTypeName.substringAfterLast('.')
        val message = rawMessage?.trim().orEmpty()
        val who = providerName?.takeIf { it.isNotBlank() }?.let { "'$it'" } ?: "This extension"

        return when (type) {
            in MISSING_CLASS_TYPES -> {
                val symbol = extractSymbol(message)
                Diagnosis(
                    kind = Kind.MISSING_CLASS,
                    symbol = symbol,
                    message = buildString {
                        append("$who needs ")
                        append(symbol ?: "a class")
                        append(", which this build does not expose to extensions. ")
                        append("This is a host runtime gap (the class was removed or renamed ")
                        append("by minification, or its library is not packaged), not a ")
                        append("provider error.")
                    },
                )
            }

            in MISSING_MEMBER_TYPES -> Diagnosis(
                kind = Kind.MISSING_MEMBER,
                symbol = extractSymbol(message),
                message = "$who was built against a different version of the CloudStream " +
                    "runtime or one of its libraries: ${message.ifEmpty { type }}.",
            )

            in LINKAGE_TYPES -> Diagnosis(
                kind = Kind.LINKAGE,
                symbol = extractSymbol(message),
                message = "$who could not be linked against this build's runtime " +
                    "($type${if (message.isEmpty()) "" else ": $message"}).",
            )

            else -> Diagnosis(
                kind = Kind.PROVIDER,
                symbol = null,
                message = message.ifEmpty { type },
            )
        }
    }

    /**
     * Pulls the offending type out of an ART/JVM linkage message.
     *
     * Handles both shapes seen in practice:
     *  - ART:  `Failed resolution of: Lkotlin/collections/SetsKt;`
     *  - JVM:  `kotlin.collections.SetsKt`
     */
    private fun extractSymbol(message: String): String? {
        if (message.isEmpty()) return null

        val descriptor = Regex("""L([A-Za-z0-9_$/]+);""").find(message)?.groupValues?.getOrNull(1)
        if (descriptor != null) return descriptor.replace('/', '.')

        // A bare binary name, possibly followed by a member signature.
        val candidate = message
            .substringAfter("Failed resolution of:", message)
            .substringBefore('(')
            .trim()
            .trimEnd('.', ':')
            .substringAfterLast(' ')
        val looksLikeClass = candidate.isNotEmpty() &&
            candidate.contains('.') &&
            candidate.none { it == '/' || it == ';' || it.isWhitespace() }
        return candidate.takeIf { looksLikeClass }
    }
}
