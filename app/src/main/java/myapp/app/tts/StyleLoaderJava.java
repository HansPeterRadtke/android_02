// C:\dev\GPT\github\android_02\app\src\main\java\myapp\app\tts\StyleLoaderJava.java
package myapp.app.tts;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Flexible style loader:
 *
 * - Supports multiple voice files: af.bin, au.bin, bf.bin, etc.
 * - Each file is float32, shape [N, 256] (N styles, 256-dim vectors).
 * - Files are stored in:
 *
 *   /sdcard/Android/data/myapp.app/files/models/voices_XX.bin
 *
 *   e.g. voices_af.bin, voices_au.bin, voices_bf.bin, ...
 *
 * - Public API is still:
 *
 *   getStyleArray(String name, int index)
 *
 *   Where "name" can be "af", "au", "bf", etc.
 *   And "index" is [0 .. N-1].
 *
 * - If requested voice/index missing, falls back to a neutral zero style.
 */
public class StyleLoaderJava {

    private static final String TAG = "StyleLoaderJava";
    private static final int STYLE_DIM = 256;

    private final Context context;

    // Cache: "af" -> float[numVoices][256]
    private final Map<String, float[][]> cache = new HashMap<>();

    public StyleLoaderJava(Context context) {
        this.context = context.getApplicationContext();
    }

    private float[][] loadVoices(String voiceName) throws IOException {
        if (voiceName == null || voiceName.isEmpty()) {
            throw new IOException("voiceName is null/empty");
        }

        // If cached, return immediately
        float[][] cached = cache.get(voiceName);
        if (cached != null) {
            return cached;
        }

        File modelsDir = context.getExternalFilesDir("models");
        if (modelsDir == null) {
            throw new IOException("getExternalFilesDir(\"models\") returned null");
        }

        String fileName = "voices_" + voiceName + ".bin";
        File voicesFile = new File(modelsDir, fileName);

        Log.d(TAG, "Loading voice '" + voiceName + "' from: " + voicesFile.getAbsolutePath());

        if (!voicesFile.exists()) {
            throw new IOException("Voices file not found: " + voicesFile.getAbsolutePath());
        }

        long length = voicesFile.length();
        if (length <= 0L) {
            throw new IOException("Voices file is empty: " + voicesFile.getAbsolutePath());
        }
        if ((length % 4L) != 0L) {
            throw new IOException("Voices file size is not a multiple of 4 bytes (float32): " + length);
        }

        int totalFloats = (int) (length / 4L);
        if ((totalFloats % STYLE_DIM) != 0) {
            throw new IOException("Total floats " + totalFloats +
                    " is not a multiple of STYLE_DIM=" + STYLE_DIM);
        }

        int numVoices = totalFloats / STYLE_DIM;
        Log.d(TAG, "Voices '" + voiceName + "': totalFloats=" + totalFloats +
                ", numVoices=" + numVoices + ", styleDim=" + STYLE_DIM);

        byte[] data = new byte[(int) length];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(voicesFile))) {
            int read = 0;
            while (read < data.length) {
                int r = in.read(data, read, data.length - read);
                if (r < 0) {
                    throw new IOException("Unexpected EOF while reading voices file");
                }
                read += r;
            }
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        float[][] voices = new float[numVoices][STYLE_DIM];
        for (int v = 0; v < numVoices; v++) {
            float[] row = voices[v];
            for (int i = 0; i < STYLE_DIM; i++) {
                row[i] = buf.getFloat();
            }
        }

        cache.put(voiceName, voices);
        Log.d(TAG, "Loaded " + numVoices + " style vectors for voice '" + voiceName + "'.");
        return voices;
    }

    public float[][] getStyleArray(String name, int index) {
        // name like "af", "au", "bf" etc. as we choose in TTS.java
        if (name == null || name.isEmpty()) {
            Log.w(TAG, "getStyleArray called with empty name, returning neutral style.");
            return neutralStyle();
        }

        try {
            float[][] voices = loadVoices(name);

            if (voices.length == 0) {
                Log.w(TAG, "Voices array empty for '" + name + "', returning neutral.");
                return neutralStyle();
            }

            int chosen = index;
            if (chosen < 0 || chosen >= voices.length) {
                chosen = 0; // default to first style
            }

            float[][] out = new float[1][STYLE_DIM];
            System.arraycopy(voices[chosen], 0, out[0], 0, STYLE_DIM);

            Log.d(TAG, "Returning style voice='" + name + "', index=" + chosen);
            return out;

        } catch (Exception e) {
            Log.e(TAG, "Error in getStyleArray('" + name + "', " + index + "): " + e.getMessage(), e);
            return neutralStyle();
        }
    }

    private float[][] neutralStyle() {
        float[][] neutral = new float[1][STYLE_DIM];
        for (int i = 0; i < STYLE_DIM; i++) {
            neutral[0][i] = 0.0f;
        }
        return neutral;
    }
}
