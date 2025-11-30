package myapp.app.tts;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java port of the Kokoro demo Tokenizer.
 *
 * NOTE:
 *  - This uses the same MAX_PHONEME_LENGTH and symbol order
 *  - VOCAB = pad + punctuation + letters + lettersIpa
 *  - tokenize() maps each char to a Long index via VOCAB, throws on unknown.
 */
public final class TokenizerJava {

    private static final int MAX_PHONEME_LENGTH = 512;

    private static final Map<String, Integer> VOCAB = buildVocab();

    private TokenizerJava() {
        // no instances
    }

    public static long[] tokenize(String phonemes) {
        if (phonemes == null) {
            phonemes = "";
        }

        if (phonemes.length() > MAX_PHONEME_LENGTH) {
            throw new IllegalArgumentException(
                    "Text is too long, must be less than " + MAX_PHONEME_LENGTH + " phonemes"
            );
        }

        long[] tmp = new long[phonemes.length()];
        int count = 0;

        for (int i = 0; i < phonemes.length(); i++) {
            String symbol = String.valueOf(phonemes.charAt(i));
            Integer id = VOCAB.get(symbol);
            if (id == null) {
                throw new IllegalArgumentException("Unknown symbol: " + symbol);
            }
            tmp[count++] = id.longValue();
        }

        if (count == tmp.length) {
            return tmp;
        }

        long[] out = new long[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    private static Map<String, Integer> buildVocab() {
        // EXACT STRINGS COPIED FROM DEMO Tokenizer.kt (pad + punctuation + letters + IPA)
        String pad = "$";
        String punctuation = ";:,.!?Â¡Â¿â€”â€¦\"Â«Â»â€œâ€ ";
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        String lettersIpa =
                "É‘ÉÉ’Ã¦É“Ê™Î²É”É•Ã§É—É–Ã°Ê¤É™É˜ÉšÉ›ÉœÉÉžÉŸÊ„É...”Ê¡Ê•Ê¢Ç€ÇÇ‚ÇƒËˆËŒËË‘Ê¼Ê´Ê°Ê±Ê²Ê·Ë Ë¤Ëžâ†“â†‘â†’â†—â†˜'Ì©'áµ»";

        String symbols = pad + punctuation + letters + lettersIpa;

        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < symbols.length(); i++) {
            String key = String.valueOf(symbols.charAt(i));
            if (!map.containsKey(key)) {
                map.put(key, map.size());
            }
        }
        return map;
    }
}