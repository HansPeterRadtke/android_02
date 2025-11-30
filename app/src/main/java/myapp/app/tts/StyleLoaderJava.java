package myapp.app.tts;

import android.content.Context;

import org.jetbrains.bio.npy.NpyArray;
import org.jetbrains.bio.npy.NpyFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Java port of the Kokoro demo StyleLoader.
 *
 * Expects the same .npy style files (af_sarah, af_bella, ...) in this app's res/raw.
 */
public final class StyleLoaderJava {

    private final Context context;

    private static final String[] NAMES = new String[] {
            "af",
            "af_bella",
            "af_nicole",
            "af_sarah",
            "af_sky",
            "am_adam",
            "am_michael",
            "bf_emma",
            "bf_isabella",
            "bm_george",
            "bm_lewis"
    };

    private final Map<String, Integer> styleResourceMap = new HashMap<>();

    public StyleLoaderJava(Context context) {
        this.context = context.getApplicationContext();
        for (String name : NAMES) {
            int resId = this.context.getResources()
                    .getIdentifier(name, "raw", this.context.getPackageName());
            if (resId == 0) {
                throw new IllegalArgumentException("Style resource '" + name + "' not found in /res/raw");
            }
            styleResourceMap.put(name, resId);
        }
    }

    /**
     * Returns style vector [1][256] for given style name and index (0..510).
     */
    public float[][] getStyleArray(String name, int index) {
        Integer resId = styleResourceMap.get(name);
        if (resId == null) {
            throw new IllegalArgumentException("Style '" + name + "' not found");
        }

        try {
            InputStream in = context.getResources().openRawResource(resId);

            File tempFile = File.createTempFile("temp_style", ".npy", context.getCacheDir());
            tempFile.deleteOnExit();

            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            } finally {
                in.close();
            }

            // org.jetbrains.bio.npy.NpyFile.read(path, maxMegabytes)
            NpyArray npyArray = NpyFile.read(tempFile.toPath(), 64);

            int[] shape = npyArray.getShape();
            if (shape.length != 3 || shape[0] != 511 || shape[1] != 1 || shape[2] != 256) {
                throw new IllegalArgumentException("Style .npy must have shape (511,1,256)");
            }

            if (index < 0 || index >= 511) {
                throw new IllegalArgumentException("Index must be between 0 and 510");
            }

            float[][] style = new float[1][256];
            float[] all = npyArray.asFloatArray();

            int base = index * 256;
            for (int i = 0; i < 256; i++) {
                style[0][i] = all[base + i];
            }

            return style;
        } catch (Exception e) {
            // Fallback: if style .npy cannot be loaded, return neutral (all-zero) style vector
            float[][] style = new float[1][256];
            for (int i = 0; i < 256; i++) {
                style[0][i] = 0.0f;
            }
            return style;
        }
    }
}
