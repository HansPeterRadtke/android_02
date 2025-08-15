package myapp.app;
import myapp.app.tts.FliteBridge;

import android.content.Context;
import android.util.Log;

import java.io.File;

class TTS {
    static { System.loadLibrary("ttsflite"); }
    private FliteBridge flite;
    public TTS(String voicePath) {
        flite = new FliteBridge();
        flite.nativeCreate(voicePath);
    }
    public int synthesize(String text, String outputPath) {
        return flite.nativeSynthesize(text, outputPath);
    }
    public int synthesize(String text) { 
        return synthesize(text, "/sdcard/output.wav"); 
    }
    public void speak(String text) {
        try {
            int result = flite.nativeSynthesize(text, "/sdcard/output.wav");
            if (result != 0) {
                Log.e("TTS", "Flite synthesis failed with code: " + result);
            } else {
                Log.i("TTS", "Flite synthesis successful, output at /sdcard/output.wav");
                // Optionally, you could add code to play the generated WAV here if desired
            }
        } catch (Exception e) {
            Log.e("TTS", "Error during Flite speak: " + e.getMessage(), e);
        }
    }
    public void stop() {
        flite.nativeStop();
    }
}