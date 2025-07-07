package myapp.app.utils;

import android.content.Context;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class WhisperRunner {
  private final Context context;

  public WhisperRunner(Context context) {
    this.context = context;
  }

  public String transcribe(byte[] audioBytes, int byteLen) {
    try {
      File encoderFile = new File(context.getFilesDir(), "whisper-encoder.onnx");
      File decoderFile = new File(context.getFilesDir(), "whisper-decoder.onnx");
      File tokensFile  = new File(context.getFilesDir(), "whisper-tokens.txt");

      if (!encoderFile.exists() || !decoderFile.exists() || !tokensFile.exists()) {
        return "ERROR: Whisper model files missing";
      }

      // NOTE: This is a placeholder
      // Actual ONNX inference would go here using ONNX Runtime for Android or Sherpa-ONNX JNI

      float[] audioData = convertToFloat(audioBytes, byteLen);

      return "[TRANSCRIPTION PLACEHOLDER] " + audioData.length + " samples processed.";
    } catch (Exception e) {
      return "EXCEPTION (WhisperRunner): " + e.toString();
    }
  }

  private float[] convertToFloat(byte[] audioBytes, int byteLen) {
    float[] out = new float[byteLen / 2];
    ByteBuffer bb = ByteBuffer.wrap(audioBytes, 0, byteLen).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < out.length; i++) {
      out[i] = bb.getShort() / 32768.0f;
    }
    return out;
  }
}