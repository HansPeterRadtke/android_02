package myapp.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import myapp.app.tts.ExternalOrtTts;
import myapp.app.tts.KokoroWaveDebug;

/**
 * Fully self-contained Kokoro TTS for myapp.app.
 *
 * - Downloads kokoro.onnx into externalFilesDir("models")/kokoro.onnx (if missing)
 * - Initializes ONNX Runtime via ExternalOrtTts (single shared OrtSession)
 * - Very simple text -> "phonemes" -> tokens pipeline (no external files)
 * - Runs ONNX (inputs: tokens, style, speed)
 * - Plays raw float PCM from the model WITHOUT extra global normalization
 */
public final class TTS {

    // ======== GLOBAL STATE ========

    private static final Object LOCK = new Object();

    private static boolean initialized = false;
    private static String lastError = null;

    private final MainActivity activity;
    private final Context appContext;

    // Model
    private static final String MODEL_FILE_NAME = "kokoro.onnx";
    // Use the working explorer URL where you actually host kokoro.onnx
    private static final String MODEL_URL = "https://g3wt.jonnyonthefly.org/explorer/kokoro.onnx";

    // ONNX interface
    private static final int SAMPLE_RATE = 22050;
    private static final int MAX_PHONEME_LENGTH = 400;
    private static final float DEFAULT_SPEED = 1.0f;

    // ======== TOKENIZER / VOCAB ========

    private static final String VOCAB_PAD      = "$";
    private static final String VOCAB_PUNCT    = " !',-.?:";
    private static final String VOCAB_LETTERS  = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String VOCAB_IPA =
            "Ã¦É‘ÉÉ’É“Ê™Î²É”É•Ã§É—É–Ã°Ê¤É™É˜ÉšÉ›ÉœÉÉžÉŸÉ É¢Ê›É£Ê°Ê±É¦Ä§É¥É§ÊœÉªáµ»É¨ÊÉ­É¬É«É®ÊŸÉ±É¯É°Å‹É³É²Ã¸ÉµÉ¸Î¸Å“É¶Ê˜É¹É¾É»Ê€ÊÉ½ÉºÊƒÊ‚ÊˆÊ§ÊŠáµ¿Ê‰Ê‹É£ÊÏ‡ÊŽÊÊ‘ÊÊ’Ê”Ê•Ê¢Ê¡É•ËˆËŒËË‘Ì†ÌƒÌ¥Ì¬Ì©ÌªÌºÌ»ÌšÌ©Ì¯Ì¹ÌœÌŸÌ ËžÌƒÌ´ÌÌžÌ˜Ì™Ì½ÌšÌ¹ÌœÌŸÌ ";

    private static final Map<Character, Integer> VOCAB_INDEX = buildVocabIndex();

    private static Map<Character, Integer> buildVocabIndex() {
        Map<Character, Integer> map = new LinkedHashMap<>();
        int idx = 0;
        String all = VOCAB_PAD + VOCAB_PUNCT + VOCAB_LETTERS + VOCAB_IPA;
        for (int i = 0; i < all.length(); i++) {
            char c = all.charAt(i);
            if (!map.containsKey(c)) {
                map.put(c, idx++);
            }
        }
        return map;
    }

    // ======== CTOR ========

    // Must match existing usage in MainActivity: new TTS(this, "...")
    public TTS(MainActivity activity, String ignoredModelPath) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        log("TTS: ctor");
    }

    // ======== LOGGING ========

    private void log(String msg) {
        if (activity != null) {
            activity.print(msg);
        }
    }

    // ======== MODEL FILE HANDLING ========

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
        return new File(modelDir, MODEL_FILE_NAME);
    }

    private boolean downloadModelToFinal(File target) {
        BufferedInputStream in = null;
        BufferedOutputStream out = null;
        try {
            String urlString = MODEL_URL;
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
            } catch (IOException ignored) {}
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {}
        }
    }

    private boolean ensureModelPresent() {
        File finalModel = getFinalModelFile();
        if (finalModel == null) {
            lastError = "TTS.ensureModelPresent: final model path is null";
            log(lastError);
            return false;
        }

        if (finalModel.exists() && finalModel.length() > 0) {
            log("TTS.ensureModelPresent: model already present at: " + finalModel.getAbsolutePath());
            return true;
        }

        log("TTS.ensureModelPresent: model missing, starting HTTP download");
        boolean ok = downloadModelToFinal(finalModel);
        if (!ok) {
            if (finalModel.exists() && finalModel.length() > 0) {
                log("TTS.ensureModelPresent: download failed but file exists: " + finalModel.getAbsolutePath());
                return true;
            }
            lastError = "TTS.ensureModelPresent: download failed, model not available";
            log(lastError);
            return false;
        }

        if (finalModel.exists() && finalModel.length() > 0) {
            log("TTS.ensureModelPresent: model downloaded to: " + finalModel.getAbsolutePath());
            return true;
        } else {
            lastError = "TTS.ensureModelPresent: model still missing after download: " + finalModel.getAbsolutePath();
            log(lastError);
            return false;
        }
    }

    private void ensureInitialized() {
        synchronized (LOCK) {
            if (initialized && ExternalOrtTts.isInitialized()) {
                return;
            }

            if (!ensureModelPresent()) {
                initialized = false;
                lastError = "TTS.ensureInitialized: model not present";
                return;
            }

            try {
                log("TTS.ensureInitialized: calling ExternalOrtTts.initialize(...)");
                ExternalOrtTts.initialize(appContext);
                initialized = true;
                lastError = null;
                log("TTS.ensureInitialized: ExternalOrtTts initialized successfully");
            } catch (Throwable t) {
                lastError = "TTS.ensureInitialized Throwable: " + t.getMessage();
                log(lastError);
                initialized = false;
            }
        }
    }

    public boolean isInitialized() {
        synchronized (LOCK) {
            return initialized && ExternalOrtTts.isInitialized();
        }
    }

    public String getLastError() {
        synchronized (LOCK) {
            return lastError;
        }
    }

    public void shutdown() {
        synchronized (LOCK) {
            log("TTS.shutdown: calling ExternalOrtTts.shutdown()");
            try {
                ExternalOrtTts.shutdown();
            } catch (Throwable ignored) {}
            initialized = false;
            lastError = null;
        }
    }

    // ======== PUBLIC API ========

    public void speak(final String text) {
        if (text == null || text.trim().isEmpty()) {
            log("TTS.speak: empty text");
            return;
        }

        new Thread("TTS-SPEAK") {
            @Override
            public void run() {
                ensureInitialized();
                if (!isInitialized()) {
                    log("TTS.speak: initialization failed, aborting. lastError=" + getLastError());
                    return;
                }

                final OrtSession session;
                try {
                    session = ExternalOrtTts.getSession();
                } catch (Throwable t) {
                    synchronized (LOCK) {
                        lastError = "TTS.speak: failed to obtain OrtSession: " + t.getMessage();
                    }
                    log(lastError);
                    return;
                }

                log("TTS.speak: session ready, text=\"" + text + "\"");

                try {
                    // 1) normalize and convert text to "phoneme string" (simple, but valid)
                    String normalized = normalizeText(text);
                    if (normalized.isEmpty()) {
                        log("TTS.speak: normalized text empty");
                        return;
                    }

                    String phonemes = normalized;
                    if (phonemes.length() > MAX_PHONEME_LENGTH) {
                        phonemes = phonemes.substring(0, MAX_PHONEME_LENGTH);
                    }

                    // 2) tokenize phonemes -> token ids
                    long[] tokens = tokenize(phonemes);
                    if (tokens.length == 0) {
                        log("TTS.speak: no tokens after tokenization");
                        return;
                    }

                    // add pad at start and end
                    long padIndex = getPadIndex();
                    long[] padded = new long[tokens.length + 2];
                    padded[0] = padIndex;
                    System.arraycopy(tokens, 0, padded, 1, tokens.length);
                    padded[padded.length - 1] = padIndex;

                    // style + speed
                    float[][] style = createStyleVector();
                    float[] speed = new float[]{DEFAULT_SPEED};

                    OrtEnvironment env = OrtEnvironment.getEnvironment();

                    long[][] tokens2D = new long[1][padded.length];
                    System.arraycopy(padded, 0, tokens2D[0], 0, padded.length);

                    OnnxTensor tokensTensor = OnnxTensor.createTensor(env, tokens2D);
                    OnnxTensor styleTensor = OnnxTensor.createTensor(env, style);
                    OnnxTensor speedTensor = OnnxTensor.createTensor(env, speed);

                    Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                    inputs.put("tokens", tokensTensor);
                    inputs.put("style", styleTensor);
                    inputs.put("speed", speedTensor);

                    log("TTS.speak: running ONNX, tokens=" + padded.length);

                    float[] audio;
                    try (OrtSession.Result result = session.run(inputs)) {
                        Object v = result.get(0).getValue();
                        if (v instanceof float[]) {
                            audio = (float[]) v;
                        } else if (v instanceof float[][]) {
                            float[][] arr2 = (float[][]) v;
                            if (arr2.length == 0) {
                                log("TTS.speak: model returned empty 2D float array");
                                return;
                            }
                            float[] row = arr2[0];
                            audio = new float[row.length];
                            System.arraycopy(row, 0, audio, 0, row.length);
                        } else {
                            log("TTS.speak: unexpected output type " + v.getClass().getName());
                            return;
                        }
                    } finally {
                        try { tokensTensor.close(); } catch (Throwable ignore) {}
                        try { styleTensor.close(); } catch (Throwable ignore) {}
                        try { speedTensor.close(); } catch (Throwable ignore) {}
                    }

                    if (audio == null || audio.length == 0) {
                        log("TTS.speak: ONNX output empty");
                        return;
                    }

                    // DEBUG: first 200 raw samples straight from ONNX
                    int dbgCount = Math.min(audio.length, 200);
                    StringBuilder sbDbg = new StringBuilder("DEBUG RAW PCM: ");
                    for (int i = 0; i < dbgCount; i++) {
                        sbDbg.append(String.format(Locale.US, "%.6f ", audio[i]));
                    }
                    log(sbDbg.toString());

                    float maxAbs = 0f;
                    for (float v : audio) {
                        float av = v >= 0f ? v : -v;
                        if (av > maxAbs) {
                            maxAbs = av;
                        }
                    }
                    log("TTS.speak: ONNX produced " + audio.length + " samples, maxAbs=" + maxAbs);

                    if (maxAbs > 1.0f) {
                        float scale = 1.0f / maxAbs;
                        for (int i = 0; i < audio.length; i++) {
                            audio[i] *= scale;
                        }
                        maxAbs = 1.0f;
                        log("TTS.speak: normalized audio by " + scale + ", new maxAbs=" + maxAbs);
                    }

                    try {
                        log("TTS.speak: saving debug WAV via KokoroWaveDebug...");
                        KokoroWaveDebug.saveAudio(audio, SAMPLE_RATE, appContext);
                    } catch (Throwable tt) {
                        log("TTS.speak: KokoroWaveDebug.saveAudio failed: " + tt.getMessage());
                    }

                    playAudio(audio, SAMPLE_RATE);

                    log("TTS.speak: playback done");
                } catch (Throwable t) {
                    synchronized (LOCK) {
                        lastError = "TTS.speak: exception: " + t.getMessage();
                    }
                    log(lastError);
                }
            }
        }.start();
    }

    // ======== TEXT NORMALIZATION ========

    private String normalizeText(String text) {
        if (text == null) return "";
        String s = text.trim();
        s = s.replaceAll("\\s+", " ");
        s = s.toLowerCase(Locale.US);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (VOCAB_INDEX.containsKey(c)) {
                sb.append(c);
            } else if (Character.isWhitespace(c)) {
                sb.append(' ');
            } else {
                sb.append(' ');
            }
        }
        String out = sb.toString().replaceAll(" +", " ").trim();
        return out.isEmpty() ? "" : out;
    }

    // ======== TOKENIZER / VOCAB ========

    private static long[] tokenize(String phonemes) {
        if (phonemes == null) phonemes = "";
        int[] tmp = new int[phonemes.length()];
        int count = 0;
        for (int i = 0; i < phonemes.length(); i++) {
            char c = phonemes.charAt(i);
            Integer id = VOCAB_INDEX.get(c);
            if (id != null) {
                tmp[count++] = id;
            }
        }
        long[] tokens = new long[count];
        for (int i = 0; i < count; i++) {
            tokens[i] = tmp[i];
        }
        return tokens;
    }

    private static long getPadIndex() {
        Integer idx = VOCAB_INDEX.get(VOCAB_PAD.charAt(0));
        return (idx == null) ? 0L : idx.longValue();
    }

    // ======== STYLE VECTOR ========

    private static float[][] createStyleVector() {
        int dim = 256;
        float[][] style = new float[1][dim];
        for (int i = 0; i < dim; i++) {
            style[0][i] = (float) ((i % 7) - 3) / 10.0f; // -0.3 .. +0.3 repeating
        }
        return style;
    }

    // ======== AUDIO PLAYBACK ========

    private void playAudio(float[] audio, int sampleRate) {
        if (audio == null || audio.length == 0) {
            log("TTS.playAudio: empty audio");
            return;
        }

        short[] pcm = new short[audio.length];
        for (int i = 0; i < audio.length; i++) {
            float v = audio[i];
            if (v > 1.0f) v = 1.0f;
            if (v < -1.0f) v = -1.0f;
            pcm[i] = (short) (v * Short.MAX_VALUE);
        }

        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBuf <= 0) {
            minBuf = pcm.length * 2;
        }

        AudioTrack track;
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .build();

            track = new AudioTrack(
                    attrs,
                    format,
                    Math.max(minBuf, pcm.length * 2),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
        } else {
            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    Math.max(minBuf, pcm.length * 2),
                    AudioTrack.MODE_STREAM
            );
        }

        try {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    track.setVolume(1.0f);
                } else {
                    track.setStereoVolume(1.0f, 1.0f);
                }
            } catch (Throwable ignored) {}

            track.play();

            int offset = 0;
            while (offset < pcm.length) {
                int written = track.write(pcm, offset, pcm.length - offset);
                if (written <= 0) break;
                offset += written;
            }
        } catch (Throwable t) {
            log("TTS.playAudio: exception " + t.getMessage());
        } finally {
            try {
                track.stop();
            } catch (Throwable ignored) {}
            try {
                track.release();
            } catch (Throwable ignored) {}
        }
    }
}
