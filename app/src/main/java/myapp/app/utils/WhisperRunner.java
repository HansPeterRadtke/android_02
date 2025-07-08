package myapp.app.utils;

import myapp.app.MainActivity;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.Result;

public class WhisperRunner {
  private final MainActivity main;

  public WhisperRunner(MainActivity main) {
    this.main = main;
  }

  public String transcribe(byte[] audioBytes, int byteLen) {
    main.print("TRANSCRIBER: Transcribe method entered.");
    OrtEnvironment env = null;
    OrtSession encoderSession = null;

    try {
      File encoderFile = new File(main.getFilesDir(), "whisper-encoder.onnx");
      File decoderFile = new File(main.getFilesDir(), "whisper-decoder.onnx");
      File tokensFile = new File(main.getFilesDir(), "whisper-tokens.txt");

      if (!encoderFile.exists()) return "ERROR: whisper-encoder.onnx missing";
      if (!decoderFile.exists()) return "ERROR: whisper-decoder.onnx missing";
      if (!tokensFile.exists()) return "ERROR: whisper-tokens.txt missing";

      main.print("TRANSCRIBER: All required model files found.");

      float[] audioData = convertToFloat(audioBytes, byteLen);
      List<String> vocab = loadVocabulary(tokensFile);
      main.print("TRANSCRIBER: Audio and vocabulary loaded. Starting session...");

      env = OrtEnvironment.getEnvironment();
      encoderSession = env.createSession(encoderFile.getAbsolutePath(), new OrtSession.SessionOptions());

      float[][] features = new float[1][audioData.length];
      System.arraycopy(audioData, 0, features[0], 0, audioData.length);

      OnnxTensor inputTensor = OnnxTensor.createTensor(env, features);
      Result result = encoderSession.run(java.util.Collections.singletonMap("audio_features", inputTensor));

      inputTensor.close();
      encoderSession.close();
      env.close();

      main.print("TRANSCRIBER: Encoder inference done. Output processed.");
      return "Whisper encoder inference executed. Output size: " + result.toString();
    } catch (OrtException e) {
      return "ORT Exception: " + e.getMessage();
    } catch (Exception e) {
      return "Exception: " + e.toString();
    } finally {
      try { if (encoderSession != null) encoderSession.close(); } catch (Exception ignored) {}
      try { if (env != null) env.close(); } catch (Exception ignored) {}
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