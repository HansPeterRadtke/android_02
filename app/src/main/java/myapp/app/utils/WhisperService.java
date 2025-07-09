package myapp.app.utils;

public class WhisperService {

  static {
    System.loadLibrary("whisper-jni");
  }

  public static native String fullTranscribe(short[] pcm, int sampleRate, String modelPath);
}