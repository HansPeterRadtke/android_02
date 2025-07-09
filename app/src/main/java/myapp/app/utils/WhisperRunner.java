package myapp.app.utils;

import myapp.app.MainActivity;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.Map;
import java.util.HashMap;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OnnxValue;

public class WhisperRunner {
  private final MainActivity main;

  public WhisperRunner(MainActivity main) {
    this.main = main;
    main.print("WHISPER: Constructor called");
  }

  public String transcribe(byte[] audioBytes, int byteLen) {
    main.print("WHISPER: Starting transcription");
    OrtEnvironment env = null;
    OrtSession encoderSession = null;
    OrtSession decoderSession = null;

    try {
      File encoderFile = new File(main.getFilesDir(), "whisper-encoder.onnx");
      File decoderFile = new File(main.getFilesDir(), "whisper-decoder.onnx");
      File tokensFile  = new File(main.getFilesDir(), "whisper-tokens.txt"  );
      main.print("WHISPER: Model files instantiated");

      if (!encoderFile.exists()) return "ERROR: whisper-encoder.onnx missing";
      if (!decoderFile.exists()) return "ERROR: whisper-decoder.onnx missing";
      if (!tokensFile.exists())  return "ERROR: whisper-tokens.txt missing";
      main.print("WHISPER: Model files exist");

      float[] audioData = convertToFloat(audioBytes, byteLen);
      main.print("WHISPER: Audio converted to float");

      List<String> vocab = loadVocabulary(tokensFile);
      main.print("WHISPER: Vocabulary loaded");

      float[][][] features = computeLogMelSpectrogram(audioData);
      main.print("WHISPER: Log-mel spectrogram computed");

      env = OrtEnvironment.getEnvironment();
      encoderSession = env.createSession(encoderFile.getAbsolutePath(), new OrtSession.SessionOptions());
      decoderSession = env.createSession(decoderFile.getAbsolutePath(), new OrtSession.SessionOptions());
      main.print("WHISPER: ONNX environment and sessions created");

      OnnxTensor inputTensor = OnnxTensor.createTensor(env, features);
      Map<String, OnnxTensor> encoderInput = new HashMap<>();
      encoderInput.put("mel", inputTensor);
      main.print("WHISPER: Encoder input prepared");

      OrtSession.Result encoderOutput = encoderSession.run(encoderInput);
      main.print("WHISPER: Encoder output received");

      OnnxTensor encoderTensor = (OnnxTensor) encoderOutput.get(0);
      Map<String, OnnxTensor> decoderInputs = new HashMap<>();
      long[] initialToken = new long[]{50258};
      OnnxTensor decoderInputTokens = OnnxTensor.createTensor(env, new long[][]{initialToken});
      decoderInputs.put("tokens", decoderInputTokens);
      decoderInputs.put("offset", OnnxTensor.createTensor(env, new long[]{0}));
      decoderInputs.put("in_n_layer_self_k_cache", OnnxTensor.createTensor(env, new float[4][1][448][384]));
      decoderInputs.put("in_n_layer_self_v_cache", OnnxTensor.createTensor(env, new float[4][1][448][384]));
      decoderInputs.put("n_layer_cross_k", encoderTensor);
      decoderInputs.put("n_layer_cross_v", encoderTensor);
      main.print("WHISPER: Decoder inputs set");

      OrtSession.Result decoderOutputs = decoderSession.run(decoderInputs);
      OnnxValue output = decoderOutputs.get(0);
      main.print("WHISPER: Decoder output received");

      inputTensor.close();
      decoderInputTokens.close();
      encoderTensor.close();
      output.close();
      encoderSession.close();
      decoderSession.close();
      env.close();
      main.print("WHISPER: Sessions and tensors closed");

      float[][][] decoderOutputArray = (float[][][]) output.getValue();
      float[] logits = decoderOutputArray[0][0];
      int maxIndex = 0;
      float maxValue = logits[0];
      for (int i = 1; i < logits.length; i++) {
        if (logits[i] > maxValue) {
          maxValue = logits[i];
          maxIndex = i;
        }
      }
      String result = vocab.get(maxIndex);
      main.print("TRANSCRIBED: " + result);
    } catch (OrtException e) {
      main.print("ORT Exception: " + e.getMessage());
      return "ORT Exception: " + e.getMessage();
    } catch (Exception e) {
      main.print("Exception: " + e.toString());
      return "Exception: " + e.toString();
    } finally {
      try { if (encoderSession != null) encoderSession.close(); } catch (Exception ignored) {}
      try { if (decoderSession != null) decoderSession.close(); } catch (Exception ignored) {}
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

  private float[][][] computeLogMelSpectrogram(float[] samples) {
    int sampleRate = 16000;
    int windowSize = 400;
    int hopSize = 160;
    int melBands = 80;
    int fftSize = 512;

    int frameCount = (samples.length - windowSize) / hopSize + 1;
    float[][] stftMag = new float[frameCount][fftSize / 2 + 1];

    for (int i = 0; i < frameCount; i++) {
      float[] windowed = new float[fftSize];
      for (int j = 0; j < windowSize; j++) {
        windowed[j] = samples[i * hopSize + j] * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * j / windowSize));
      }
      float[] real = Arrays.copyOf(windowed, fftSize);
      float[] imag = new float[fftSize];
      fft(real, imag);
      for (int j = 0; j <= fftSize / 2; j++) {
        stftMag[i][j] = (float) Math.sqrt(real[j] * real[j] + imag[j] * imag[j]);
      }
    }

    float[][] melFB = melFilterBank(melBands, fftSize, sampleRate);
    float[][] melSpec = new float[melBands][frameCount];
    for (int m = 0; m < melBands; m++) {
      for (int t = 0; t < frameCount; t++) {
        float sum = 0;
        for (int f = 0; f <= fftSize / 2; f++) {
          sum += melFB[m][f] * stftMag[t][f];
        }
        melSpec[m][t] = (float) Math.log10(Math.max(sum, 1e-10));
      }
    }

    float[][][] result = new float[1][melBands][frameCount];
    for (int m = 0; m < melBands; m++) {
      for (int t = 0; t < frameCount; t++) {
        result[0][m][t] = melSpec[m][t];
      }
    }
    return result;
  }

  private void fft(float[] real, float[] imag) {
    int n = real.length;
    for (int i = 0, j = 0; i < n; ++i) {
      if (i < j) {
        float tmpReal = real[i];
        float tmpImag = imag[i];
        real[i] = real[j];
        imag[i] = imag[j];
        real[j] = tmpReal;
        imag[j] = tmpImag;
      }
      int m = n >> 1;
      while (j >= m && m >= 2) {
        j -= m;
        m >>= 1;
      }
      j += m;
    }
    for (int m = 2; m <= n; m <<= 1) {
      float angle = (float) (-2 * Math.PI / m);
      float wReal = (float) Math.cos(angle);
      float wImag = (float) Math.sin(angle);
      for (int k = 0; k < n; k += m) {
        float wr = 1, wi = 0;
        for (int j = 0; j < m / 2; ++j) {
          int t = k + j;
          int u = t + m / 2;
          float tr = wr * real[u] - wi * imag[u];
          float ti = wr * imag[u] + wi * real[u];
          real[u] = real[t] - tr;
          imag[u] = imag[t] - ti;
          real[t] += tr;
          imag[t] += ti;
          float tempWr = wr;
          wr = wr * wReal - wi * wImag;
          wi = tempWr * wImag + wi * wReal;
        }
      }
    }
  }

  private float[][] melFilterBank(int melBands, int fftSize, int sampleRate) {
    float[][] fb = new float[melBands][fftSize / 2 + 1];
    float fMin = 0;
    float fMax = sampleRate / 2;
    float melMin = hzToMel(fMin);
    float melMax = hzToMel(fMax);
    float[] melPoints = new float[melBands + 2];
    for (int i = 0; i < melPoints.length; i++) {
      melPoints[i] = melMin + (melMax - melMin) * i / (melBands + 1);
    }
    float[] hzPoints = new float[melPoints.length];
    for (int i = 0; i < hzPoints.length; i++) {
      hzPoints[i] = melToHz(melPoints[i]);
    }
    int[] bins = new int[hzPoints.length];
    for (int i = 0; i < bins.length; i++) {
      bins[i] = (int) Math.floor((fftSize + 1) * hzPoints[i] / sampleRate);
    }
    for (int m = 1; m <= melBands; m++) {
      int f_m_minus = bins[m - 1];
      int f_m = bins[m];
      int f_m_plus = bins[m + 1];
      for (int k = f_m_minus; k < f_m; k++) {
        fb[m - 1][k] = (float) (k - f_m_minus) / (f_m - f_m_minus);
      }
      for (int k = f_m; k < f_m_plus; k++) {
        fb[m - 1][k] = (float) (f_m_plus - k) / (f_m_plus - f_m);
      }
    }
    return fb;
  }

  private float hzToMel(float hz) {
    return 2595 * (float) Math.log10(1 + hz / 700);
  }

  private float melToHz(float mel) {
    return 700 * ((float) Math.pow(10, mel / 2595) - 1);
  }
}