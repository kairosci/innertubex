package com.metrolist.innertubex.cipher

/**
 * Parser for extracting cipher functions from YouTube player JavaScript.
 *
 * Heuristic extraction similar in spirit to yt-dlp: find split/join transform
 * functions and the object helpers they use. N-transform and signature decipher
 * often share a similar shape; we disambiguate by order (second match) and body checks.
 */
internal object PlayerScriptParser {
    data class ParseResult(
        val nFunctionCode: String?,
        /** JS expression that invokes the n-transform, e.g. variable name `abc`. */
        val nInvoker: String?,
        val sigFunctionCode: String?,
        /** JS expression that invokes the signature decipher, e.g. variable name `xyz`. */
        val sigInvoker: String?,
        val helperFunctions: Map<String, String>,
    )

    private val splitJoinFuncPattern =
        Regex(
            """(?:var\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*function\s*\(\s*a\s*\)\s*\{[^}]*a\s*=\s*a\.split\s*\(\s*""\s*\)[^}]*return\s+a\.join\s*\(\s*""\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /**
     * N-parameter transform: first split/join-style function in the player is often (not always) the n-function.
     */
    fun extractNFunction(playerCode: String): Pair<String?, String?> = extractSplitJoinByIndex(playerCode, index = 0)

    /**
     * Signature decipher: next split/join function after the n-transform that has distinct body.
     */
    fun extractSigFunction(
        playerCode: String,
        nCode: String?,
    ): Pair<String?, String?> {
        val matches = splitJoinFuncPattern.findAll(playerCode).toList()
        for (i in 1 until matches.size) {
            val functionName = matches[i].groupValues[1]
            val func = extractFunctionByName(playerCode, functionName) ?: continue
            if (func != nCode) return func to functionName
        }
        if (matches.isNotEmpty() && nCode == null) {
            extractSplitJoinByIndex(playerCode, index = 1).let { if (it.first != null) return it }
        }

        val altPattern =
            Regex(
                """function\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\(\s*a\s*\)\s*\{[^{}]*a\s*[=.][^{}]*reverse[^{}]*\}""",
                RegexOption.DOT_MATCHES_ALL,
            )
        altPattern.findAll(playerCode).forEach { m ->
            val funcName = m.groupValues[1]
            val func = extractFunctionByName(playerCode, funcName)
            if (func != null && func != nCode && (func.contains("reverse") || func.contains("splice") || func.contains("slice"))) {
                return func to funcName
            }
        }
        return null to null
    }

    private fun extractSplitJoinByIndex(
        playerCode: String,
        index: Int,
    ): Pair<String?, String?> {
        val matches = splitJoinFuncPattern.findAll(playerCode).toList()
        if (matches.size <= index) return null to null
        val functionName = matches[index].groupValues[1]
        val func = extractFunctionByName(playerCode, functionName) ?: return null to null
        return func to functionName
    }

    fun extractHelperFunctions(playerCode: String): Map<String, String> {
        val helpers = mutableMapOf<String, String>()
        val helperPatterns =
            listOf(
                Regex(
                    """var\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*\{\s*[^}]*reverse[^}]*\}""",
                    RegexOption.DOT_MATCHES_ALL,
                ),
                Regex("""var\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*\{\s*[^}]*slice[^}]*\}""", RegexOption.DOT_MATCHES_ALL),
                Regex(
                    """var\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*\{\s*[^}]*splice[^}]*\}""",
                    RegexOption.DOT_MATCHES_ALL,
                ),
            )
        for (pattern in helperPatterns) {
            pattern.findAll(playerCode).forEach { match ->
                val startIdx = match.range.first
                val endIdx = findFunctionEnd(playerCode, startIdx)
                if (endIdx > startIdx) {
                    val fullFunc = playerCode.substring(startIdx, endIdx)
                    val funcName = match.groupValues[1]
                    helpers[funcName] = fullFunc
                }
            }
        }
        return helpers
    }

    fun parse(playerCode: String): ParseResult {
        val (nCode, nInv) = extractNFunction(playerCode)
        val (sigCode, sigInv) = extractSigFunction(playerCode, nCode)
        return ParseResult(
            nFunctionCode = nCode,
            nInvoker = nInv,
            sigFunctionCode = sigCode,
            sigInvoker = sigInv,
            helperFunctions = extractHelperFunctions(playerCode),
        )
    }

    private fun extractFunctionByName(
        playerCode: String,
        functionName: String,
    ): String? {
        val patterns =
            listOf(
                Regex(
                    """var\s+${Regex.escape(functionName)}\s*=\s*function\s*\([^)]*\)\s*\{""",
                    RegexOption.DOT_MATCHES_ALL,
                ),
                Regex("""function\s+${Regex.escape(functionName)}\s*\([^)]*\)\s*\{""", RegexOption.DOT_MATCHES_ALL),
                Regex("""${Regex.escape(functionName)}\s*=\s*function\s*\([^)]*\)\s*\{""", RegexOption.DOT_MATCHES_ALL),
            )
        for (pattern in patterns) {
            val match = pattern.find(playerCode)
            if (match != null) {
                val startIdx = match.range.first
                val endIdx = findFunctionEnd(playerCode, startIdx)
                if (endIdx > startIdx) {
                    return playerCode.substring(startIdx, endIdx)
                }
            }
        }
        return null
    }

    private fun findFunctionEnd(
        code: String,
        startIdx: Int,
    ): Int {
        var braceCount = 0
        var inString = false
        var stringChar: Char? = null
        for (i in startIdx until code.length) {
            val char = code[i]
            when {
                !inString && (char == '"' || char == '\'' || char == '`') -> {
                    inString = true
                    stringChar = char
                }

                inString && char == stringChar && code.getOrNull(i - 1) != '\\' -> {
                    inString = false
                    stringChar = null
                }

                !inString && char == '{' -> {
                    braceCount++
                }

                !inString && char == '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        return i + 1
                    }
                }
            }
        }
        return code.length
    }

    fun generateSolverScript(parseResult: ParseResult): String {
        val sb = StringBuilder()
        parseResult.helperFunctions.forEach { (_, code) ->
            sb.appendLine(code)
        }

        parseResult.nFunctionCode?.let { code ->
            sb.appendLine(code)
            val inv = parseResult.nInvoker
            if (!inv.isNullOrBlank()) {
                sb.appendLine(
                    """
                    function _solveN(input) {
                        try { return $inv(input); } catch(e) { return null; }
                    }
                    """.trimIndent(),
                )
            }
        }

        parseResult.sigFunctionCode?.let { code ->
            sb.appendLine(code)
            val inv = parseResult.sigInvoker
            if (!inv.isNullOrBlank()) {
                sb.appendLine(
                    """
                    function _solveSig(input) {
                        try { return $inv(input); } catch(e) { return null; }
                    }
                    """.trimIndent(),
                )
            }
        }

        return sb.toString()
    }
}
