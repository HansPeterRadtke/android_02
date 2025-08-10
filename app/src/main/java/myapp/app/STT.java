package myapp.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaRecorder;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class STT {

  private final MainActivity main;
  private Model model;
  private Recognizer recognizer;

  private final int sampleRate = 16000;
  private final byte[] recordedData = new byte[sampleRate * 2 * 60 * 15];
  private int recordedBytes = 0;
  private int playbackPosition = 0;

  private boolean isRecording = false;
  private boolean isPlaying = false;
  private boolean isLive = false;

  private AudioRecord recorder;
  private AudioTrack player;
  private AudioRecord liveRecorder;
  private Thread liveThread;
  private final Object lock = new Object();
  private StringBuilder liveBuffer = new StringBuilder();

  public STT(MainActivity main) {
    this.main = main;
  }

  public void setModel(Model model) {
    this.model = model;
    try {
      recognizer = new Recognizer(model, 16000.0f);
      main.print("(STT) Model and recognizer initialized");
    } catch (Exception e) {
      main.print("EXCEPTION(STT init): " + e);
    }
  }

  public boolean isRecording() { return isRecording; }
  public boolean isPlaying() { return isPlaying; }
  public boolean isLive() { return isLive; }

  public void startRecording() {
    try {
      int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
      recorder.startRecording();
      isRecording = true;
      new Thread(() -> {
        int offset;
        synchronized (lock) { offset = recordedBytes; }
        int chunkSize = 8192;
        while (isRecording && (offset < recordedData.length)) {
          int read = recorder.read(recordedData, offset, Math.min(chunkSize, recordedData.length - offset));
          if (read > 0) {
            offset += read;
            synchronized (lock) { recordedBytes = offset; }
            float seconds = ((float) offset / (sampleRate * 2));
            main.print(String.format("RECORD: read=%d offset=%d dur=%.2fs rms=%.1f dBFS", read, offset, seconds, rmsDb(recordedData, offset - read, read)));
          }
        }
      }).start();
    } catch (Exception e) {
      main.print("EXCEPTION(RECORD): " + e);
    }
  }

  public void stopRecording() {
    try {
      isRecording = false;
      if (recorder != null) {
        recorder.stop();
        recorder.release();
        recorder = null;
      }
    } catch (Exception e) {
      main.print("EXCEPTION(stopRecording): " + e);
    }
  }

  public void startPlayback() {
    try {
      synchronized (lock) { if (playbackPosition >= recordedBytes) playbackPosition = 0; }
      int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      player = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
      player.play();
      isPlaying = true;
      new Thread(() -> {
        int chunkSize = 1024;
        int localPosition;
        synchronized (lock) { localPosition = playbackPosition; }
        while (isPlaying) {
          int toWrite;
          synchronized (lock) { toWrite = Math.min(chunkSize, recordedBytes - localPosition); }
          if (toWrite <= 0) break;
          player.write(recordedData, localPosition, toWrite);
          localPosition += toWrite;
          synchronized (lock) { playbackPosition = localPosition; }
        }
        stopPlayback();
      }).start();
    } catch (Exception e) {
      main.print("EXCEPTION(PLAY): " + e);
    }
  }

  public void stopPlayback() {
    try {
      isPlaying = false;
      if (player != null) {
        player.stop();
        player.release();
        player = null;
      }
    } catch (Exception e) {
      main.print("EXCEPTION(stopPlayback): " + e);
    }
  }

  public void startLiveTranscription() {
    try {
      if (model == null || recognizer == null) {
        main.print("Cannot start live transcription: model not loaded");
        return;
      }
      int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      liveRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
      main.print("LIVE: recorder state=" + liveRecorder.getState());
      liveRecorder.startRecording();
      isLive = true;
      main.print("LIVE: started");
      liveBuffer.setLength(0);
      liveThread = new Thread(() -> {
        byte[] buf = new byte[bufferSize];
        float lastPartialLog = nowSec();
        long totalRead = 0;
        try {
          main.print("LIVE: started loop");
          while (isLive) {
            int read = liveRecorder.read(buf, 0, buf.length);
            if (read <= 0) {
              main.print("LIVE: read=" + read);
              continue;
            }
            totalRead += read;
            boolean hasFinal = recognizer.acceptWaveForm(buf, read);
            if (hasFinal) {
              String j = recognizer.getResult();
              String fin = extractTextJson(j, false);
              main.print("LIVE: acceptWaveForm=true bytes=" + read + " rms=" + String.format("%.1f", rmsDb(buf, 0, read)) + " finalJson=" + trimForLog(j));
              if (!fin.isEmpty()) {
                if (liveBuffer.length() > 0) liveBuffer.append(' ');
                liveBuffer.append(fin);
                main.setLiveText(liveBuffer.toString());
              } else {
                main.print("LIVE: final text empty");
              }
            } else {
              float now = nowSec();
              if ((now - lastPartialLog) >= 0.25f) {
                String pjson = recognizer.getPartialResult();
                String part = extractTextJson(pjson, true);
                main.print("LIVE: acceptWaveForm=false bytes=" + read + " rms=" + String.format("%.1f", rmsDb(buf, 0, read)) + " partialJson=" + trimForLog(pjson) + " part='" + part + "'");
                String shown = part.isEmpty() ? liveBuffer.toString() : (liveBuffer.length() > 0 ? (liveBuffer.toString() + " " + part) : part);
                if (!shown.isEmpty()) main.setLiveText(shown);
                lastPartialLog = now;
              }
            }
          }
          String finJson = recognizer.getFinalResult();
          String fin = extractTextJson(finJson, false);
          main.print("LIVE: finalFlush json=" + trimForLog(finJson) + " text='" + fin + "'");
          if (!fin.isEmpty()) {
            if (liveBuffer.length() > 0) liveBuffer.append(' ');
            liveBuffer.append(fin);
            main.setLiveText(liveBuffer.toString());
          }
        } catch (Exception e) {
          main.print("EXCEPTION(LIVE loop): " + e);
        } finally {
          try {
            if (liveRecorder != null) {
              liveRecorder.stop();
              liveRecorder.release();
            }
          } catch (Exception ignore) {}
          liveRecorder = null;
          main.runOnUiThread(() -> main.setLiveButtonText("Start Live Transcription"));
          main.print("LIVE: stopped totalRead=" + totalRead);
        }
      });
      liveThread.start();
    } catch (Exception e) {
      main.print("EXCEPTION(startLive): " + e);
      isLive = false;
      try { if (liveRecorder != null) { liveRecorder.release(); liveRecorder = null; } } catch (Exception ignore) {}
    }
  }

  public void stopLiveTranscription() {
    isLive = false;
    if (liveThread != null) {
      try { liveThread.join(1000); } catch (InterruptedException ignored) {}
      liveThread = null;
    }
  }

  public void toText() {
    try {
      if (model == null || recognizer == null) {
        main.print("Cannot convert to text: model not loaded");
        return;
      }
      ByteArrayInputStream bais;
      synchronized (lock) {
        bais = new ByteArrayInputStream(recordedData, 0, recordedBytes);
      }
      byte[] buffer = new byte[4096];
      resetBuffer();
      while (true) {
        int read = bais.read(buffer);
        if (read == -1) break;
        if (recognizer.acceptWaveForm(buffer, read)) {
          main.print(extractTextJson(recognizer.getResult(), false));
        } else {
          main.print(extractTextJson(recognizer.getPartialResult(), true));
        }
      }
      main.print(extractTextJson(recognizer.getFinalResult(), false));
    } catch (IOException e) {
      main.print("EXCEPTION(toText): " + e);
    }
  }

  private String extractTextJson(String json, boolean partial) {
    try {
      if (json == null || json.isEmpty()) return "";
      JSONObject o = new JSONObject(json);
      String key = partial ? "partial" : "text";
      if (!o.has(key)) return "";
      String s = o.optString(key, "");
      if (s == null) return "";
      s = s.replace("\n", " ").replace("\t", " ").trim();
      return s;
    } catch (Exception e) {
      return "";
    }
  }

  private String trimForLog(String s) {
    if (s == null) return "null";
    if (s.length() > 160) return s.substring(0, 160) + "...";
    return s;
  }

  private float rmsDb(byte[] data, int offset, int len) {
    if (len <= 1) return -120f;
    long sum = 0;
    int samples = 0;
    int end = offset + len;
    for (int i = offset; i + 1 < end; i += 2) {
      int lo = data[i] & 0xFF;
      int hi = data[i + 1];
      short s = (short)((hi << 8) | lo);
      int v = s;
      sum += (long)v * (long)v;
      samples++;
    }
    if (samples == 0) return -120f;
    double mean = sum / (double) samples;
    double rms = Math.sqrt(mean);
    double db = 20.0 * Math.log10(rms / 32768.0 + 1e-12);
    return (float) db;
  }

  private void resetBuffer() {
    synchronized (lock) {
      recordedBytes = 0;
      playbackPosition = 0;
    }
    main.print("BUFFER: Reset complete");
  }

  private float nowSec() {
    return (float) (System.nanoTime() / 1_000_000_000.0);
  }
}