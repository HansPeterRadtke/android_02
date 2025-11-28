package myapp.app;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import myapp.app.tts.ExternalOrtTts;


public final class TTS {

    private static final Object LOCK = new Object();

    // We keep these fields for compatibility, but Kokoro is managed by ExternalOrtTts.
    private static OrtEnvironment env = null;
    private static OrtSession session = null;
    private static boolean initialized = false;
    private static String lastError = null;

    private final MainActivity activity;
    private final Context appContext;

    // KEEP SIGNATURE COMPATIBLE WITH MainActivity:
    // tts = new TTS(this, "/sdcard/Android/data/your.package.name/files/models/kokoro.onnx");
    // second argument is ignored
    public TTS(MainActivity activity, String ignoredModelPath) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        log("TTS: ctor");
    }

    private void log(String msg) {
        activity.print(msg);
    }

    // final model location:
    //   <appContext.getExternalFilesDir("models")>/kokoro.onnx
    private File getFinalModelFile() {
        if (appContext == null) {
            log("TTS.getFinalModelFile: appContext is null");
            return null;
        }
        File modelDir = appContext.getExternalFilesDir("models");
        if (modelDir == null) {
            log("TTS.getFinalModelFile: getExternalFilesDir(\"models\") returned null");
            return null;
        }
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            log("TTS.getFinalModelFile: failed to create models dir: " + modelDir.getAbsolutePath());
            return null;
        }
        return new File(modelDir, "kokoro.onnx");
    }

    // download from your HTTP explorer directly into app's private dir
    private boolean downloadModelToFinal(File target) {
        BufferedInputStream in = null;
        BufferedOutputStream out = null;

        try {
            String urlString = "https://g3.jonnyonthefly.org/explorer/upload/kokoro.onnx";
            log("TTS.downloadModelToFinal: downloading from " + urlString);
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                lastError = "TTS.downloadModelToFinal: HTTP " + code;
                log(lastError);
                conn.disconnect();
                return false;
            }

            in = new BufferedInputStream(conn.getInputStream());
            FileOutputStream fos = new FileOutputStream(target);
            out = new BufferedOutputStream(fos);

            byte[] buf = new byte[8192];
            int r;
            long total = 0L;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                total += r;
            }
            out.flush();
            conn.disconnect();

            log("TTS.downloadModelToFinal: download finished, bytes=" + total);
            return true;
        } catch (IOException e) {
            lastError = "TTS.downloadModelToFinal IO error: " + e.getMessage();
            log(lastError);
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ensure model exists at final location (download once if missing)
    private boolean ensureModelPresent() {
        File finalModel = getFinalModelFile();
        if (finalModel == null) {
            lastError = "TTS.ensureModelPresent: final model path is null";
            log(lastError);
            return false;
        }

        if (finalModel.exists()) {
            log("TTS.ensureModelPresent: model already present at: " + finalModel.getAbsolutePath());
            return true;
        }

        log("TTS.ensureModelPresent: model missing, starting HTTP download");
        boolean ok = downloadModelToFinal(finalModel);
        if (!ok) {
            if (finalModel.exists()) {
                log("TTS.ensureModelPresent: download failed but file exists: " + finalModel.getAbsolutePath());
                return true;
            }
            lastError = "TTS.ensureModelPresent: download failed, model not available";
            log(lastError);
            return false;
        }

        if (finalModel.exists()) {
            log("TTS.ensureModelPresent: model downloaded to: " + finalModel.getAbsolutePath());
            return true;
        } else {
            lastError = "TTS.ensureModelPresent: model still missing after download: " + finalModel.getAbsolutePath();
            log(lastError);
            return false;
        }
    }

    /**
     * Ensure that:
     *  - the ONNX model file exists in externalFilesDir("models")/kokoro.onnx (download if needed)
     *  - ExternalOrtTts has been initialized once with this appContext
     *
     * We DO NOT create or close OrtEnvironment/OrtSession here directly.
     * All ORT lifetime is delegated to ExternalOrtTts.
     */
    private void ensureInitialized() {
        synchronized (LOCK) {
            // If we already successfully initialized and ExternalOrtTts is still initialized, nothing to do.
            if (initialized && ExternalOrtTts.isInitialized()) {
                return;
            }

            // Make sure the model file is there (download once if needed)
            if (!ensureModelPresent()) {
                initialized = false;
                env = null;
                session = null;
                return;
            }

            try {
                log("TTS.ensureInitialized: calling ExternalOrtTts.initialize(...)");
                ExternalOrtTts.initialize(appContext);

                // We don't store environment/session here; ExternalOrtTts owns them.
                env = null;
                session = null;

                initialized = true;
                lastError = null;
                log("TTS.ensureInitialized: ExternalOrtTts initialized successfully");
            } catch (Throwable t) {
                lastError = "TTS.ensureInitialized Throwable: " + t.getMessage();
                log(lastError);
                initialized = false;
                env = null;
                session = null;
            }
        }
    }

    public boolean isInitialized() {
        synchronized (LOCK) {
            // Treat initialized == true AND ExternalOrtTts.isInitialized() == true as "OK".
            return initialized && ExternalOrtTts.isInitialized();
        }
    }

    public String getLastError() {
        synchronized (LOCK) {
            return lastError;
        }
    }

    // ONLY place where ORT objects are closed (delegated to ExternalOrtTts)
    public void shutdown() {
        synchronized (LOCK) {
            log("TTS.shutdown: calling ExternalOrtTts.shutdown()");
            try {
                ExternalOrtTts.shutdown();
            } catch (Throwable ignored) {
            }

            // Keep these null for compatibility; they are not used for Kokoro anymore.
            session = null;
            env = null;
            initialized = false;
            lastError = null;
        }
    }

    public void speak(final String text) {
        if (text == null || text.isEmpty()) {
            log("TTS.speak: empty text");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                // Make sure the model exists and ExternalOrtTts is initialized exactly once.
                ensureInitialized();

                if (!isInitialized()) {
                    log("TTS.speak: initialization failed, aborting. lastError=" + getLastError());
                    return;
                }

                final OrtSession kokoroSession;
                try {
                    kokoroSession = ExternalOrtTts.getSession();
                } catch (Throwable t) {
                    synchronized (LOCK) {
                        lastError = "TTS.speak: failed to obtain Kokoro OrtSession: " + t.getMessage();
                    }
                    log(lastError);
                    return;
                }

                // At this point:
                //  - ExternalOrtTts has a live OrtEnvironment and OrtSession
                //  - The ONNX model file is located at:
                //        <externalFilesDir(\"models\")>/kokoro.onnx
                //
                // The actual Kokoro inference and audio playback code should use kokoroSession.
                // This class does not close kokoroSession; it is managed by ExternalOrtTts.
                log("TTS.speak: Kokoro session ready, text length=" + text.length());

                // TODO: hook your existing Kokoro inference + audio playback pipeline here,
                // using 'kokoroSession' as the OrtSession for scoring.
                // Do not close kokoroSession here.
            }
        }, "TTS-SPEAK").start();
    }
}
