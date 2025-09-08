package myapp.app;
import myapp.app.tts.FliteBridge;

import android.util.Log;

class TTS {
    static { System.loadLibrary("ttsflite"); }
    private FliteBridge flite;
    private MainActivity main;

    public TTS(MainActivity main, String voicePath) {
    this.main = main;
    main.print("[TTS] Constructor start — delaying library load by 1s...");
    try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
    main.print("[TTS] Library loaded successfully, continuing setup...");
    this.main = main;
    main.print("[TTS] Constructor start — delaying library load by 1s...");
    try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
    main.print("[TTS] Library loaded successfully, continuing setup...");
        this.main = main;
        main.print("[TTS] Constructor started.");
        try {
            flite = new FliteBridge();
            main.print("[TTS] FliteBridge instance created.");
            flite.nativeCreate(voicePath);
            main.print("[TTS] Native create called with voicePath: " + voicePath);
        } catch (Exception e) {
            main.print("[TTS] Error in constructor: " + e.getMessage());
            Log.e("TTS", "Error during constructor", e);
        }
        main.print("[TTS] Constructor finished.");
    }

    public int synthesize(String text, String outputPath) {
        main.print("[TTS] synthesize called. Text: " + text + ", OutputPath: " + outputPath);
        int result = flite.nativeSynthesize(text, outputPath);
        main.print("[TTS] synthesize finished with result: " + result);
        return result;
    }

    public int synthesize(String text) { 
        main.print("[TTS] synthesize (default path) called.");
        return synthesize(text, "/sdcard/output.wav"); 
    }

    public void speak(String text) {
        main.print("[TTS] speak called. Text: " + text);
        try {
            int result = flite.nativeSynthesize(text, "/sdcard/output.wav");
            main.print("[TTS] speak finished with result: " + result);
            if (result != 0) {
                Log.e("TTS", "Flite synthesis failed with code: " + result);
            } else {
                Log.i("TTS", "Flite synthesis successful, output at /sdcard/output.wav");
            }
        } catch (Exception e) {
            main.print("[TTS] Error during speak: " + e.getMessage());
            Log.e("TTS", "Error during Flite speak", e);
        }
    }

    public void stop() {
        main.print("[TTS] stop called.");
        flite.nativeStop();
        main.print("[TTS] stop finished.");
    }
}
