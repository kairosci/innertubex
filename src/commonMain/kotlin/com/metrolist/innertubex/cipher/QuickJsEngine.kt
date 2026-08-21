package com.metrolist.innertubex.cipher

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wrapper around the QuickJS engine for executing JavaScript code.
 * This is used to run the YouTube player cipher deobfuscation logic.
 *
 * Based on yt-dlp's QuickJS integration approach.
 */
internal class QuickJsEngine {
    companion object {
        private const val EVALUATION_TIMEOUT_MS = 10_000L

        /** Valid JavaScript double-quoted string literal for passing into evaluated calls. */
        internal fun jsStringLiteral(s: String): String =
            buildString(s.length + 2) {
                append('"')
                for (c in s) {
                    when (c) {
                        '\\' -> {
                            append("\\\\")
                        }

                        '"' -> {
                            append("\\\"")
                        }

                        '\n' -> {
                            append("\\n")
                        }

                        '\r' -> {
                            append("\\r")
                        }

                        '\t' -> {
                            append("\\t")
                        }

                        else -> {
                            if (c.code < 0x20) {
                                append("\\u%04x".format(c.code))
                            } else {
                                append(c)
                            }
                        }
                    }
                }
                append('"')
            }
    }

    private val mutex = Mutex()
    private var quickJs: QuickJs? = null

    /**
     * Initialize the QuickJS runtime.
     */
    suspend fun initialize() =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (quickJs == null) {
                    quickJs =
                        QuickJs
                            .create(Dispatchers.Default)
                            .also { it.evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS }
                }
            }
        }

    /**
     * Execute JavaScript code and return the result.
     *
     * @param code The JavaScript code to execute
     * @return The result of the execution as a string
     */
    suspend fun evaluate(code: String): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val runtime = quickJs ?: throw IllegalStateException("QuickJS not initialized")
                val result: Any? = runtime.evaluate<Any?>(code)
                result?.toString() ?: ""
            }
        }

    /** Execute JavaScript for side effects without asking QuickJS to marshal the final value. */
    suspend fun execute(code: String) {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val runtime = quickJs ?: throw IllegalStateException("QuickJS not initialized")
                runtime.evaluate<Any?>("$code\n;undefined;")
            }
        }
    }

    /**
     * Execute a JavaScript function with parameters.
     *
     * @param functionName The name of the function to call
     * @param params The parameters to pass to the function
     * @return The result of the function call
     */
    suspend fun callFunction(
        functionName: String,
        vararg params: Any?,
    ): Any? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val runtime = quickJs ?: throw IllegalStateException("QuickJS not initialized")
                val paramsStr =
                    params.joinToString(", ") { param ->
                        when (param) {
                            is String -> jsStringLiteral(param)
                            is Number -> param.toString()
                            null -> "null"
                            else -> param.toString()
                        }
                    }
                runtime.evaluate("$functionName($paramsStr)")
            }
        }

    /**
     * Set up the global environment for YouTube player execution.
     * This creates necessary globals like XMLHttpRequest, URL, location, etc.
     */
    suspend fun setupYoutubeGlobals() =
        withContext(Dispatchers.Default) {
            val setupCode =
                """
                if (typeof globalThis.XMLHttpRequest === "undefined") {
                    globalThis.XMLHttpRequest = { prototype: {} };
                }
                if (typeof URL === "undefined") {
                    globalThis.location = {
                        hash: "",
                        host: "www.youtube.com",
                        hostname: "www.youtube.com",
                        href: "https://www.youtube.com/watch?v=yt-dlp-wins",
                        origin: "https://www.youtube.com",
                        password: "",
                        pathname: "/watch",
                        port: "",
                        protocol: "https:",
                        search: "?v=yt-dlp-wins",
                        username: "",
                    };
                } else {
                    globalThis.location = new URL("https://www.youtube.com/watch?v=yt-dlp-wins");
                }
                if (typeof globalThis.document === "undefined") {
                    globalThis.document = Object.create(null);
                }
                if (typeof globalThis.navigator === "undefined") {
                    globalThis.navigator = Object.create(null);
                }
                if (typeof globalThis.self === "undefined") {
                    globalThis.self = globalThis;
                }
                if (typeof globalThis.window === "undefined") {
                    globalThis.window = globalThis;
                }
                if (typeof globalThis.Intl === "undefined") {
                    const NumberFormat = function(locale, options) {
                        this.options = options || {};
                    };
                    NumberFormat.supportedLocalesOf = function(locales) {
                        return Array.isArray(locales) ? locales : [locales];
                    };
                    NumberFormat.prototype.format = function(value) {
                        let formatted = String(value);
                        const minimumDigits = this.options.minimumIntegerDigits || 0;
                        while (formatted.length < minimumDigits) formatted = "0" + formatted;
                        return formatted;
                    };
                    const DateTimeFormat = function() {};
                    DateTimeFormat.prototype.resolvedOptions = function() {
                        return { timeZone: "UTC" };
                    };
                    DateTimeFormat.prototype.format = function(value) {
                        return String(value);
                    };
                    globalThis.Intl = { NumberFormat, DateTimeFormat };
                }
                """.trimIndent()

            execute(setupCode)
        }

    /**
     * Load the YouTube player JavaScript code into the engine.
     *
     * @param playerCode The YouTube player JavaScript code
     */
    suspend fun loadPlayerScript(playerCode: String) =
        withContext(Dispatchers.Default) {
            // First setup globals
            setupYoutubeGlobals()

            // Then load the player code
            execute(playerCode)
        }

    /**
     * Clean up and release resources.
     */
    suspend fun dispose() =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                quickJs?.close()
                quickJs = null
            }
        }
}
