package myapp.app.tts;

public class FliteBridge {
    static { System.loadLibrary("ttsflite"); }

    public native int nativeCreate(String voicePath);
    public native int nativeSynthesize(String text, String outputPath);
    public native void nativeStop();
}
