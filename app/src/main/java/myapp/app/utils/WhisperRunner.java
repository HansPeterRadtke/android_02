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
      File tokensFile  = new File(main.getFilesDir(), "whisper-tokens.txt"  );

      if (!encoderFile.exists()) return "ERROR: whisper-encoder.onnx missing";
      if (!decoderFile.exists()) return "ERROR: whisper-decoder.onnx missing";
      if (!tokensFile .exists()) return "ERROR: whisper-tokens.txt missing"  ;

      main.print("TRANSCRIBER: All required model files found.");

      float[] audioData = convertToFloat(audioBytes, byteLen);
      List<String> vocab = loadVocabulary(tokensFile);
      main.print("TRANSCRIBER: Audio and vocabulary loaded. Starting session...");

      env = OrtEnvironment.getEnvironment();
      main.print("TRANSCRIBER: after getEnvironment");
      encoderSession = env.createSession(encoderFile.getAbsolutePath(), new OrtSession.SessionOptions());
      main.print("TRANSCRIBER: after env.createSession");

      float[][] features = new float[1][audioData.length];
      System.arraycopy(audioData, 0, features[0], 0, audioData.length);
      main.print("TRANSCRIBER: after System.arraycopy");

      OnnxTensor inputTensor = OnnxTensor.createTensor(env, features);
      main.print("TRANSCRIBER: after OnnxTensor.createTensor");
      Result result = encoderSession.run(java.util.Collections.singletonMap("audio_features", inputTensor));
      main.print("TRANSCRIBER: after encoderSession.run");
      main.print("TRANSCRIBER: result:" + result.toString());

      inputTensor.close();
      main.print("TRANSCRIBER: after inputTensor.close");
      encoderSession.close();
      main.print("TRANSCRIBER: after encoderSession.close");
      env.close();
      main.print("TRANSCRIBER: after env.close");

      main.print("TRANSCRIBER: Encoder inference done. Output processed.");
      return "Whisper encoder inference executed. Output size: " + result.toString();
    } catch (OrtException e) {
      return "ORT Exception: " + e.getMessage();
    } catch (Exception e) {
      return "Exception: " + e.toString();
    } finally {
      try {
        if(encoderSession != null) encoderSession.close();
        } catch (Exception e) {
          return e.toString();
        }
      try {
        if(env != null) env.close();
      } catch (Exception e) {
        return e.toString();
      }
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