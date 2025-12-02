package myapp.app.tts;

import android.content.Context
import android.content.res.Resources
import java.io.IOException

/**
 * PhonemeConverter for android_02.
 *
 * - Loads cmudict_ipa from res/raw using packageName (no direct R import).
 * - Uses CMU IPA entries when available.
 * - For missing words, uses a simple built-in grapheme→IPA fallback.
 * - Does NOT depend on com.github.medavox.ipa_transcribers.*
 */
class PhonemeConverter(private val context: Context) {
    private val phonemeMap = mutableMapOf<String, String>()

    init {
        loadDictionary()
    }

    private fun loadDictionary() {
        try {
            val res = context.resources
            val pkg = context.packageName
            val id = res.getIdentifier("cmudict_ipa", "raw", pkg)

            if (id == 0) {
                println("PhonemeConverter: cmudict_ipa raw resource NOT FOUND (package=$pkg)")
                return
            }

            res.openRawResource(id).bufferedReader()
                .useLines { lines ->
                    lines
                        .filter { it.isNotBlank() && !it.startsWith(";;;") && !it.startsWith(";") }
                        .forEach { line ->
                            val parts = line.split("\t", limit = 2)
                            if (parts.size == 2) {
                                phonemeMap[parts[0]] = parts[1]
                            } else {
                                println("PhonemeConverter: invalid dict line: $line")
                            }
                        }
                }
            println("PhonemeConverter: dictionary loaded, entries=${phonemeMap.size}")
        } catch (e: IOException) {
            println("PhonemeConverter: error loading dictionary: ${e.message}")
            e.printStackTrace()
        } catch (e: Resources.NotFoundException) {
            println("PhonemeConverter: cmudict_ipa not found: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun convertToPhonemes(word: String): String {
        // Keep punctuation as-is
        if (word.matches(Regex("[^a-zA-Z']+"))) {
            return word
        }

        // CMU keys are uppercase, no punctuation
        val cleanWord = word.replace(Regex("[^a-zA-Z']"), "").uppercase()
        if (cleanWord.isEmpty()) return word

        // In cmudict_ipa, stress digits (0/1/2) appear on vowels; replace with a generic primary marker
        val key = cleanWord.replace(Regex("[0-9]"), "ˈ")

        val dictHit = phonemeMap[key]
        if (dictHit != null) {
            // Use the first variant
            return dictHit.split(",").first().trim()
        }

        // Fallback: rough grapheme→IPA mapping
        return fallbackTranscribe(word)
    }

    /**
     * Very simple built-in fallback.
     * Not perfect, but better than raw letters and avoids external deps.
     */
    private fun fallbackTranscribe(word: String): String {
        val w = word.lowercase()
        val out = StringBuilder()

        var i = 0
        while (i < w.length) {
            val c = w[i]

            // Basic digraphs first
            if (i + 1 < w.length) {
                val two = w.substring(i, i + 2)
                when (two) {
                    "ch" -> { out.append("tʃ"); i += 2; continue }
                    "sh" -> { out.append("ʃ");  i += 2; continue }
                    "th" -> { out.append("θ");  i += 2; continue }
                    "ph" -> { out.append("f");  i += 2; continue }
                    "ng" -> { out.append("ŋ");  i += 2; continue }
                }
            }

            // Single letters
            val ipa = when (c) {
                'a' -> "æ"
                'b' -> "b"
                'c' -> "k"
                'd' -> "d"
                'e' -> "ɛ"
                'f' -> "f"
                'g' -> "g"
                'h' -> "h"
                'i' -> "ɪ"
                'j' -> "dʒ"
                'k' -> "k"
                'l' -> "l"
                'm' -> "m"
                'n' -> "n"
                'o' -> "ɒ"
                'p' -> "p"
                'q' -> "k"
                'r' -> "ɹ"
                's' -> "s"
                't' -> "t"
                'u' -> "ʊ"
                'v' -> "v"
                'w' -> "w"
                'x' -> "ks"
                'y' -> "j"
                'z' -> "z"
                else -> c.toString() // keep punctuation / digits
            }
            out.append(ipa)
            i++
        }

        return out.toString()
    }

    fun phonemize(text: String, lang: String = "en-us", norm: Boolean = true): String {
        val normalized = if (norm) normalizeText(text) else text
        println("PhonemeConverter.phonemize: normalized=\"$normalized\"")

        // Split into tokens but keep punctuation as separate tokens
        val tokens = normalized.split(Regex("(?<=\\W)|(?=\\W)"))
            .filter { it.isNotBlank() }

        val result = StringBuilder()
        tokens.forEachIndexed { index, token ->
            val ipa = if (token.matches(Regex("[^a-zA-Z']+"))) {
                token
            } else {
                val tmp = convertToPhonemes(token)
                    .replace(" ", "")
                    .replace("ˌ", "")
                adjustStressMarkers(tmp)
            }

            if (index > 0 && !token.matches(Regex("[^a-zA-Z']+"))) {
                result.append(" ")
            }
            result.append(ipa)
        }

        return postProcessPhonemes(result.toString(), lang)
    }

    fun adjustStressMarkers(input: String): String {
        val vowels = setOf(
            'a','e','i','o','u',
            'ɑ','ɐ','ɔ','æ','ɒ','ə','ɨ','ɯ','ɛ','œ','ɝ','ɞ','ɪ','ʊ','ʌ'
        )

        val builder = StringBuilder(input)
        var i = 0

        while (i < builder.length) {
            if (builder[i] == 'ˈ' || builder[i] == 'ˌ') {
                val stressIndex = i
                val stressChar = builder[i]
                for (j in stressIndex + 1 until builder.length) {
                    if (builder[j] in vowels) {
                        builder.deleteCharAt(stressIndex)
                        builder.insert(j - 1, stressChar)
                        i = j
                        break
                    }
                }
            }
            i++
        }

        return builder.toString()
    }

    private fun normalizeText(text: String): String {
        var normalized = text
            .lines()
            .joinToString("\n") { it.trim() }
            .replace("[‘’]".toRegex(), "'")
            .replace("[“”«»]".toRegex(), "\"")
            .replace("[、。？！：；]".toRegex()) { match ->
                when (match.value) {
                    "、" -> ","
                    "。" -> "."
                    "？" -> "?"
                    "！" -> "!"
                    "：" -> ":"
                    "；" -> ";"
                    else -> match.value
                } + " "
            }

        normalized = normalized
            .replace(Regex("\\bD[Rr]\\.(?= [A-Z])"), "Doctor")
            .replace(Regex("\\b(?:Mr\\.|MR\\.(?= [A-Z]))"), "Mister")
            .replace(Regex("\\b(?:Ms\\.|MS\\.(?= [A-Z]))"), "Miss")
            .replace(Regex("\\b(?:Mrs\\.|MRS\\.(?= [A-Z]))"), "Mrs")
            .replace(Regex("\\betc\\.(?! [A-Z])"), "etc")

        normalized = normalized.replace(Regex("(?<=\\d),(?=\\d)"), "")
        normalized = normalized.replace(Regex("(?<=\\d)-(?=\\d)"), " to ")

        return normalized.trim()
    }

    private fun postProcessPhonemes(phonemes: String, lang: String): String {
        var result = phonemes
            .replace("r", "ɹ")

        // Kokoro-specific fixes (kept from demo)
        result = result.replace("kəkˈoɹoʊ", "kˈoʊkəɹoʊ")
            .replace("kəkˈɔɹəʊ", "kˈəʊkəɹəʊ")

        if (lang == "en-us") {
            result = result.replace("ti", "di")
        }

        return result.trim()
    }
}