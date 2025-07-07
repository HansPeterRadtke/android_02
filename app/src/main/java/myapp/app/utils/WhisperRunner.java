package myapp.app.utils;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class WhisperRunner {
  private final Context context;

  public WhisperRunner(Context context) {
    this.context = context;
  }

  public String transcribe(byte[] audioBytes, int byteLen) {
    try {
      File encoderFile = new File(context.getFilesDir(), "whisper-encoder.onnx");
      File decoderFile = new File(context.getFilesDir(), "whisper-decoder.onnx");
      File tokensFile = new File(context.getFilesDir(), "whisper-tokens.txt");

      if (!encoderFile.exists()) return "ERROR: whisper-encoder.onnx missing";
      if (!decoderFile.exists()) return "ERROR: whisper-decoder.onnx missing";
      if (!tokensFile.exists()) return "ERROR: whisper-tokens.txt missing";

      float[] audioData = convertToFloat(audioBytes, byteLen);
      List<String> vocab = loadVocabulary(tokensFile);

      // TODO: Implement ONNX loading, encoder execution, decoder loop, token output

      return "OK: Prepared " + audioData.length + " samples, vocab size: " + vocab.size();
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

  private List<String> loadVocabulary(File file) throws Exception {
    List<String> list = new ArrayList<>();
    try (Scanner scanner = new Scanner(new FileInputStream(file))) {
      while (scanner.hasNextLine()) {
        list.add(scanner.nextLine());
      }
    }
    return list;
  }
}