@file:Suppress("unused")

package android.text

import java.util.regex.Pattern

/**
 * JVM unit-test stand-in for the framework's `android.text.TextUtils`.
 *
 * media3's own code calls TextUtils from pure-JVM paths our tests
 * exercise (`Format.Builder.build()`, `MimeTypes` codec mapping), but
 * the mockable android.jar stub makes every method throw
 * "not mocked". Test classes precede that stub jar on the unit-test
 * classpath, so this fixture shadows it and provides the REAL,
 * trivial semantics of the methods media3 common uses.
 *
 * This fakes nothing about any test outcome: production code runs
 * unmodified with the same behavior it has on a device.
 */
class TextUtils private constructor() {

    companion object {

        @JvmStatic
        fun isEmpty(str: CharSequence?): Boolean = str == null || str.length == 0

        @JvmStatic
        fun getTrimmedLength(s: CharSequence): Int {
            var start = 0
            var end = s.length
            while (start < end && s[start].isWhitespace()) start++
            while (end > start && s[end - 1].isWhitespace()) end--
            return end - start
        }

        @JvmStatic
        fun equals(a: CharSequence?, b: CharSequence?): Boolean {
            if (a === b) return true
            if (a == null || b == null) return false
            if (a.length != b.length) return false
            for (i in a.indices) {
                if (a[i] != b[i]) return false
            }
            return true
        }

        @JvmStatic
        fun isDigitsOnly(str: CharSequence): Boolean = str.all { it in '0'..'9' }

        @JvmStatic
        fun join(delimiter: CharSequence, tokens: Iterable<*>): String =
            tokens.joinToString(delimiter.toString())

        @JvmStatic
        fun join(delimiter: CharSequence, tokens: Array<out Any?>): String =
            tokens.joinToString(delimiter.toString())

        @JvmStatic
        fun split(text: String, expression: String): Array<String> =
            text.split(expression.toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        @JvmStatic
        fun split(text: String, pattern: Pattern): Array<String> = pattern.split(text)
    }
}
