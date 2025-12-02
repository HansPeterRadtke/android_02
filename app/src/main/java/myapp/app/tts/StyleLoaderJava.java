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

/**
 * Style loader that reads Kokoro voice style vectors from a single .bin file
 * in the same "models" directory as kokoro.onnx.
 *
 * EXPECTED FILE LOCATION ON DEVICE:
 *   /storage/emulated/0/Android/data/myapp.app/files/models/voices_af.bin
 *
 * EXPECTED FORMAT:
 *   - float32 (little-endian)
 *   - length = N * 256 floats (N style vectors, each of size 256)
 *
 * TTS STILL CALLS:
 *   StyleLoaderJava loader = new StyleLoaderJava(context);
 *   float[][] style = loader.getStyleArray("af_sarah", 0);
 *
 * We IGNORE name/index and always return voices[0] as a [1][256] style array.
 */
public class StyleLoaderJava {

    private static final String TAG = "StyleLoaderJava";

    // Name of the voices file next to kokoro.onnx in "models" dir
    private static final String VOICES_FILE_NAME = "voices_af.bin";
    private static final int STYLE_DIM = 256;

    private final Context context;
    private float[][] voices; // [numVoices][256]

    public StyleLoaderJava(Context context) {
        this.context = context.getApplicationContext();
        try {
            loadVoicesFromExternalModelsDir();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load style vectors from " + VOICES_FILE_NAME + ": " + e.getMessage(), e);
            // Fallback: keep voices == null so getStyleArray() can return neutral zeros
            this.voices = null;
        }
    }

    private void loadVoicesFromExternalModelsDir() throws IOException {
        File modelsDir = context.getExternalFilesDir("models");
        if (modelsDir == null) {
            throw new IOException("getExternalFilesDir(\"models\") returned null");
        }

        File voicesFile = new File(modelsDir, VOICES_FILE_NAME);
        Log.d(TAG, "Loading voices from: " + voicesFile.getAbsolutePath());

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
        Log.d(TAG, "Voices file OK: totalFloats=" + totalFloats +
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

        float[][] tmpVoices = new float[numVoices][STYLE_DIM];
        for (int v = 0; v < numVoices; v++) {
            float[] row = tmpVoices[v];
            for (int i = 0; i < STYLE_DIM; i++) {
                row[i] = buf.getFloat();
            }
        }

        this.voices = tmpVoices;
        Log.d(TAG, "Loaded " + numVoices + " style vectors from voices file.");
    }

    /**
     * Public API kept compatible with earlier code:
     *   getStyleArray(String name, int index)
     *
     * We ignore name/index and always:
     *   - if voices available: return voices[0] as [1][256]
     *   - else: return neutral zero style [1][256]
     */
    public float[][] getStyleArray(String name, int index) {
        if (voices == null || voices.length == 0) {
            Log.w(TAG, "No style vectors loaded, returning neutral zero style.");
            float[][] neutral = new float[1][STYLE_DIM];
            for (int i = 0; i < STYLE_DIM; i++) {
                neutral[0][i] = 0.0f;
            }
            return neutral;
        }

        int chosen = 0;
        if (index >= 0 && index < voices.length) {
            chosen = index;
        }

        float[][] out = new float[1][STYLE_DIM];
        System.arraycopy(voices[chosen], 0, out[0], 0, STYLE_DIM);

        Log.d(TAG, "Returning style vector index=" + chosen + ", name=" + name);
        return out;
    }
}
