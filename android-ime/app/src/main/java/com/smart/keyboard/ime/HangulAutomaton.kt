package com.smart.keyboard.ime

/**
 * Android Native Hangul Automaton Engine
 * Fully ports the web simulator's robust and complete Jamo Assembly algorithm.
 * Handles split consonants, compound vowels, and compound final consonants.
 */
class HangulAutomaton {

    companion object {
        val CHO_LIST = listOf(
            "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
        )

        val JUNG_LIST = listOf(
            "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ", "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ"
        )

        val JONG_LIST = listOf(
            "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
        )

        val COMPOUND_VOWELS = mapOf(
            "ㅗㅏ" to "ㅘ",
            "ㅗㅐ" to "ㅙ",
            "ㅗㅣ" to "ㅚ",
            "ㅜㅓ" to "ㅝ",
            "ㅜㅔ" to "ㅞ",
            "ㅜㅣ" to "ㅟ",
            "ㅡㅣ" to "ㅢ",
            "ㅏㅣ" to "ㅐ",
            "ㅓㅣ" to "ㅔ",
            "ㅑㅣ" to "ㅒ",
            "ㅕㅣ" to "ㅖ",
            "ㅘㅣ" to "ㅙ",
            "ㅝㅣ" to "ㅞ"
        )

        val COMPOUND_JONGS = mapOf(
            "ㄱㅅ" to "ㄳ",
            "ㄴㅈ" to "ㄵ",
            "ㄴㅎ" to "ㄶ",
            "ㄹㄱ" to "ㄺ",
            "ㄹㅁ" to "ㄻ",
            "ㄹㅂ" to "ㄼ",
            "ㄹㅅ" to "ㄽ",
            "ㄹㅌ" to "ㄾ",
            "ㄹㅍ" to "ㄿ",
            "ㄹㅎ" to "ㅀ",
            "ㅂㅅ" to "ㅄ"
        )
    }

    /**
     * Checks if a Jamo is a vowel.
     */
    fun isVowel(jamo: String): Boolean {
        val extraVowels = listOf("ㅣ", "·", "ㅡ", "ㅏ", "ㅓ", "ㅗ", "ㅜ", "ㅛ", "ㅠ", "ㅑ", "ㅕ", "ㅐ", "ㅔ", "ㅒ", "ㅖ", "ㅘ", "ㅙ", "ㅚ", "ㅝ", "ㅞ", "ㅟ", "ㅢ")
        return JUNG_LIST.contains(jamo) || extraVowels.contains(jamo)
    }

    /**
     * Reconstructs compound vowel from Cheonjiin vowel keys.
     * Inputs: List of "ㅣ", "·", "ㅡ"
     */
    fun composeCheonjiinVowels(keys: List<String>): String {
        var text = keys.joinToString("")

        val replacements = listOf(
            "ㅣ··" to "ㅑ",
            "··ㅣ" to "ㅕ",
            "ㅡ··" to "ㅠ",
            "··ㅡ" to "ㅛ",
            "ㅣ·" to "ㅏ",
            "·ㅣ" to "ㅓ",
            "ㅡ·" to "ㅜ",
            "·ㅡ" to "ㅗ",
            "ㅣㅡ" to "ㅢ"
        )

        for ((pattern, replacement) in replacements) {
            while (text.contains(pattern)) {
                text = text.replace(pattern, replacement)
            }
        }

        val chars = text.map { it.toString() }
        val resolved = mutableListOf<String>()

        for (current in chars) {
            if (resolved.isNotEmpty()) {
                val last = resolved.last()
                val combined = last + current
                if (COMPOUND_VOWELS.containsKey(combined)) {
                    resolved[resolved.size - 1] = COMPOUND_VOWELS[combined]!!
                    continue
                }
            }
            resolved.add(current)
        }

        return resolved.joinToString("")
    }

    /**
     * Assembles a flat list of Jamos into a fully composed Korean String.
     */
    fun assembleJamos(jamoSeq: List<String>): String {
        if (jamoSeq.isEmpty()) return ""

        val result = java.lang.StringBuilder()
        var i = 0

        while (i < jamoSeq.size) {
            var cho = -1
            var jung = -1
            var jong = -1

            val charStr = jamoSeq[i]
            if (isVowel(charStr)) {
                // Vowel starts without a consonant
                var vowel = charStr
                i++
                while (i < jamoSeq.size && isVowel(jamoSeq[i])) {
                    val combined = vowel + jamoSeq[i]
                    if (COMPOUND_VOWELS.containsKey(combined)) {
                        vowel = COMPOUND_VOWELS[combined]!!
                        i++
                    } else {
                        break
                    }
                }
                result.append(vowel)
                continue
            }

            cho = CHO_LIST.indexOf(charStr)
            if (cho == -1) {
                // Non-Hangul or punctuation
                result.append(charStr)
                i++
                continue
            }

            val choChar = charStr
            i++

            // Expect Vowel for Jung
            if (i < jamoSeq.size && isVowel(jamoSeq[i])) {
                var vowel = jamoSeq[i]
                i++
                while (i < jamoSeq.size && isVowel(jamoSeq[i])) {
                    val combined = vowel + jamoSeq[i]
                    if (COMPOUND_VOWELS.containsKey(combined)) {
                        vowel = COMPOUND_VOWELS[combined]!!
                        i++
                    } else {
                        break
                    }
                }
                jung = JUNG_LIST.indexOf(vowel)

                // Expect Jong (Final) or next syllables
                if (i < jamoSeq.size && !isVowel(jamoSeq[i])) {
                    val nextConsonant = jamoSeq[i]
                    var hasVowelAfter = false

                    if (i + 1 < jamoSeq.size && isVowel(jamoSeq[i + 1])) {
                        hasVowelAfter = true
                    }

                    if (hasVowelAfter) {
                        val code = 0xAC00 + (cho * 21 + jung) * 28
                        result.append(code.toChar())
                        continue
                    } else {
                        val tempJong = JONG_LIST.indexOf(nextConsonant)
                        if (tempJong != -1) {
                            jong = tempJong
                            i++

                            // Check compound final consonant
                            if (i < jamoSeq.size && !isVowel(jamoSeq[i])) {
                                val nextNextConsonant = jamoSeq[i]
                                val hasVowelAfter2 = (i + 1 < jamoSeq.size && isVowel(jamoSeq[i + 1]))

                                if (hasVowelAfter2) {
                                    val compoundKey = nextConsonant + nextNextConsonant
                                    if (COMPOUND_JONGS.containsKey(compoundKey)) {
                                        val splitFirst = JONG_LIST.indexOf(nextConsonant)
                                        val code = 0xAC00 + (cho * 21 + jung) * 28 + splitFirst
                                        result.append(code.toChar())
                                        continue
                                    }
                                } else {
                                    val compoundKey = nextConsonant + nextNextConsonant
                                    if (COMPOUND_JONGS.containsKey(compoundKey)) {
                                        jong = JONG_LIST.indexOf(COMPOUND_JONGS[compoundKey]!!)
                                        i++
                                    }
                                }
                            }
                            val code = 0xAC00 + (cho * 21 + jung) * 28 + jong
                            result.append(code.toChar())
                        } else {
                            val code = 0xAC00 + (cho * 21 + jung) * 28
                            result.append(code.toChar())
                        }
                    }
                } else {
                    val code = 0xAC00 + (cho * 21 + jung) * 28
                    result.append(code.toChar())
                }
            } else {
                result.append(choChar)
            }
        }

        return result.toString()
    }
}
