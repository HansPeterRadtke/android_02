package myapp.app;

import myapp.app.utils.ModelDownloader;
import org.vosk.Model;
import org.vosk.Recognizer;

import android.Manifest;
import android.os.Build ;
import android.os.Bundle;
import android.app.Activity;
import android.view.View     ;
import android.view.ViewGroup;
import android.widget.Button      ;
import android.widget.TextView    ;
import android.widget.ScrollView  ;
import android.widget.LinearLayout;
import android.media.AudioTrack   ;
import android.media.AudioFormat  ;
import android.media.AudioRecord  ;
import android.media.AudioManager ;
import android.media.MediaRecorder;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;

public class MainActivity extends Activity {
  private TextView      statusText;
  private TextView      liveText;
  private ScrollView    liveScroll;
  private Button        recordButton;
  private Button        playButton;
  private Button        readTextButton;
  private Button        liveButton;
  private Button        toTextButton;
  private AudioRecord   recorder;
  private AudioTrack    player;
  private AudioRecord   liveRecorder;
  private Thread        liveThread;
  private boolean       isRecording  = false;
  private boolean       isPlaying    = false;
  private boolean       isLive       = false;
  private int           sampleRate   = 16000;
  private final byte[]  recordedData     = new byte[sampleRate * 2 * 60 * 15];
  private int           recordedBytes    = 0;
  private int           playbackPosition = 0;
  private final Object  lock             = new Object();
  private Recognizer    recognizer;
  private Model         model;

  private static final int PERMISSION_REQUEST_CODE = 200;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);

    recordButton = new Button(this);
    recordButton.setText("Start Recording");
    layout.addView(recordButton);

    playButton = new Button(this);
    playButton.setText("Play Recorded Audio");
    layout.addView(playButton);

    readTextButton = new Button(this);
    readTextButton.setText("Read Text");
    layout.addView(readTextButton);

    toTextButton = new Button(this);
    toTextButton.setText("To Text");
    layout.addView(toTextButton);

    liveButton = new Button(this);
    liveButton.setText("Start Live Transcription");
    layout.addView(liveButton);

    // Live field directly under the buttons, same behavior as status, min 1 line, max 10 lines
    liveText = new TextView(this);
    liveText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    liveText.setTextIsSelectable(true);
    liveText.setSingleLine(false);
    liveText.setMinLines(1);
    liveText.setMaxLines(10);
    liveText.setText("");
    liveScroll = new ScrollView(this);
    liveScroll.setFillViewport(true);
    liveScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    liveScroll.addView(liveText);
    layout.addView(liveScroll);

    // Existing debug/status field below (takes remaining height)
    statusText = new TextView(this);
    statusText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    statusText.setTextIsSelectable(true);
    statusText.setSingleLine(false);
    statusText.setMaxLines(Integer.MAX_VALUE);
    ScrollView statusScroll = new ScrollView(this);
    statusScroll.setFillViewport(true);
    statusScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    statusScroll.addView(statusText);
    layout.addView(statusScroll);

    setContentView(layout);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
      }
    }

    recordButton.setOnClickListener(v -> {
      if (isLive) { print("LIVE: Already running; stop it first."); return; }
      if (!isRecording) startRecording(); else stopRecording();
    });

    playButton.setOnClickListener(v -> {
      if (isLive) { print("LIVE: Already running; stop it first."); return; }
      if (!isPlaying) startPlayback(); else stopPlayback();
    });

    readTextButton.setOnClickListener(v -> { print("Read Text pressed"); });

    toTextButton.setOnClickListener(v -> {
      try {
        if (recognizer == null) { print("ERROR: recognizer == null"); return; }
        float startTranscription = nowSec();
        synchronized (lock) {
          if (recordedBytes > 0) {
            boolean done = recognizer.acceptWaveForm(recordedData, recordedBytes);
            print("toText: acceptWaveForm=" + done);
            print(recognizer.getFinalResult());
          } else {
            print("toText: no recorded audio");
          }
        }
        float dur = nowSec() - startTranscription;
        float audioSec = ((float) recordedBytes / (sampleRate * 2));
        print(String.format("(toText) dur=%.3fs audio=%.3fs xRT=%.3f", dur, audioSec, audioSec > 0 ? dur / audioSec : -1f));
        resetBuffer();
      } catch (Exception e) {
        print("EXCEPTION(toText): " + e);
      }
    });

    liveButton.setOnClickListener(v -> { if (!isLive) startLiveTranscription(); else stopLiveTranscription(); });

    // Synchronous download + init at end of onCreate (same timing as before)
    print("(onCreate) Creating ModelDownloader");
    ModelDownloader md = new ModelDownloader(this);
    print("(onCreate) Starting ModelDownloader");
    md.run();
    while (!md.done) {
      try { Thread.sleep(200); } catch (InterruptedException e) { print("Interrupted while waiting for model download"); }
    }
    print("(onCreate) md.done == " + md.done);

    try {
      float t0 = nowSec();
      print("(onCreate) Creating Model at " + getFilesDir() + "/" + ModelDownloader.VOSK_MODEL_NAME);
      File modelRoot = new File(getFilesDir(), ModelDownloader.VOSK_MODEL_NAME);
      model = new Model(modelRoot.getAbsolutePath());
      print(String.format("(onCreate) Model created in %.3fs", nowSec() - t0));

      t0 = nowSec();
      recognizer = new Recognizer(model, 16000.0f);
      print(String.format("(onCreate) Recognizer created in %.3fs", nowSec() - t0));
    } catch (Exception e) {
      print("EXCEPTION(init): " + e);
    }
  }

  public void print(String msg) {
    synchronized (lock) {
      runOnUiThread(() -> { statusText.append(msg + "\n"); statusText.invalidate(); });
    }
  }

  private void setLiveText(String text) {
    runOnUiThread(() -> {
      liveText.setText(text);
      liveScroll.post(() -> liveScroll.fullScroll(View.FOCUS_DOWN));
    });
  }

  private void startRecording() {
    try {
      int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      print("RECORD: minBuffer=" + min);
      recorder = new AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        Math.max(min, 8192)
      );
      print("RECORD: state=" + recorder.getState());
      recorder.startRecording();
      isRecording = true;
      recordButton.setText("Stop Recording");

      new Thread(() -> {
        int offset;
        synchronized (lock) { offset = recordedBytes; }
        int   chunkSize     = 8192;
        float lastPrintTime = nowSec();
        while(isRecording && (offset < recordedData.length)) {
          int read = recorder.read(recordedData, offset, Math.min(chunkSize, (recordedData.length - offset)));
          if (read > 0) {
            offset += read;
            synchronized (lock) { recordedBytes = offset; }
            float seconds = ((float) offset / (sampleRate * 2));
            if (nowSec() - lastPrintTime >= 2.0f) {
              print(String.format("RECORD: read=%d offset=%d dur=%.2fs rms=%.1f dBFS", read, offset, seconds, rmsDb(recordedData, offset - read, read)));
              lastPrintTime = nowSec();
            }
          } else {
            print("RECORD: read=" + read);
          }
        }
        runOnUiThread(() -> recordButton.setText("Start Recording"));
      }).start();
    } catch (Exception e) {
      print("EXCEPTION(RECORD): " + e);
    }
  }

  private void stopRecording() {
    try {
      isRecording = false;
      if (recorder != null) {
        recorder.stop();
        recorder.release();
        recorder = null;
        float duration_sec = ((float) recordedBytes / (sampleRate * 2));
        print("(stopRecording) duration_sec = " + duration_sec);
      }
    } catch (Exception e) {
      print("EXCEPTION(stopRecording): " + e);
    }
  }

  private void startPlayback() {
    try {
      synchronized (lock) { if (playbackPosition >= recordedBytes) playbackPosition = 0; }
      int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      player         = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
      print("PLAY: bufferSize=" + bufferSize);
      player.play();
      isPlaying = true;
      playButton.setText("Stop Playback");

      new Thread(() -> {
        int chunkSize = 1024;
        int localPosition;
        synchronized (lock) { localPosition = playbackPosition; }
        float lastPrintTime = nowSec();
        while(isPlaying) {
          int toWrite;
          int recorded;
          synchronized (lock) {
            toWrite  = Math.min(chunkSize, (recordedBytes - localPosition));
            recorded = recordedBytes;
          }
          if(toWrite <= 0) break;
          player.write(recordedData, localPosition, toWrite);
          localPosition += toWrite;
          synchronized (lock) { playbackPosition = localPosition; }
          if(nowSec() - lastPrintTime >= 2.0f) {
            float  seconds = (float) recorded / (sampleRate * 2);
            print(String.format("PLAY: wrote=%d pos=%d dur=%.2fs", toWrite, localPosition, seconds));
            lastPrintTime  = nowSec();
          }
        }
        runOnUiThread(this::stopPlayback);
      }).start();
    } catch (Exception e) {
      print("EXCEPTION(PLAY): " + e);
    }
  }

  private void stopPlayback() {
    try {
      isPlaying = false;
      if (player != null) {
        player.stop();
        player.release();
        player = null;
      }
      playButton.setText("Play Recorded Audio");
    } catch (Exception e) {
      runOnUiThread(() -> print("EXCEPTION(stopPlayback): " + e));
    }
  }

  private void startLiveTranscription() {
    if (recognizer == null) { print("ERROR: recognizer == null"); return; }
    if (isRecording || isPlaying) { print("ERROR: stop recording/playback first"); return; }

    try {
      int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      int chunk  = Math.max(minBuf, 4096); // ~128 ms @ 16 kHz
      print("LIVE: minBuf=" + minBuf + " chunk=" + chunk);
      liveRecorder = new AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        chunk * 4
      );
      print("LIVE: recorder state=" + liveRecorder.getState());
      liveRecorder.startRecording();
      isLive = true;
      liveButton.setText("Stop Live Transcription");
      setLiveText("");

      final StringBuilder liveBuffer = new StringBuilder();

      liveThread = new Thread(() -> {
        byte[] buf = new byte[chunk];
        float  lastPartialLog = nowSec();
        long   totalRead = 0;
        try {
          print("LIVE: started loop");
          while (isLive) {
            int read = liveRecorder.read(buf, 0, buf.length);
            if (read <= 0) {
              print("LIVE: read=" + read);
              continue;
            }
            totalRead += read;

            boolean hasFinal = recognizer.acceptWaveForm(buf, read);
            if (hasFinal) {
              String j  = recognizer.getResult();
              String fin = extractTextJson(j, false);
              print("LIVE: acceptWaveForm=true bytes=" + read + " rms=" + String.format("%.1f", rmsDb(buf, 0, read)) + " finalJson=" + trimForLog(j));
              if (!fin.isEmpty()) {
                if (liveBuffer.length() > 0) liveBuffer.append(' ');
                liveBuffer.append(fin);
                setLiveText(liveBuffer.toString());
              } else {
                print("LIVE: final text empty");
              }
            } else {
              float now = nowSec();
              if ((now - lastPartialLog) >= 0.25f) {
                String pjson = recognizer.getPartialResult();
                String part  = extractTextJson(pjson, true);
                print("LIVE: acceptWaveForm=false bytes=" + read + " rms=" + String.format("%.1f", rmsDb(buf, 0, read)) + " partialJson=" + trimForLog(pjson) + " part='" + part + "'");
                String shown = part.isEmpty()
                               ? liveBuffer.toString()
                               : (liveBuffer.length() > 0 ? (liveBuffer.toString() + " " + part) : part);
                if (!shown.isEmpty()) setLiveText(shown);
                lastPartialLog = now;
              }
            }
          }

          String finJson = recognizer.getFinalResult();
          String fin     = extractTextJson(finJson, false);
          print("LIVE: finalFlush json=" + trimForLog(finJson) + " text='" + fin + "'");
          if (!fin.isEmpty()) {
            if (liveBuffer.length() > 0) liveBuffer.append(' ');
            liveBuffer.append(fin);
            setLiveText(liveBuffer.toString());
          }
        } catch (Exception e) {
          print("EXCEPTION(LIVE loop): " + e);
        } finally {
          try {
            if (liveRecorder != null) {
              liveRecorder.stop();
              liveRecorder.release();
            }
          } catch (Exception ignore) {}
          liveRecorder = null;
          runOnUiThread(() -> liveButton.setText("Start Live Transcription"));
          print("LIVE: stopped totalRead=" + totalRead);
        }
      });

      liveThread.start();
      print("LIVE: started");
    } catch (Exception e) {
      print("EXCEPTION(startLive): " + e);
      isLive = false;
      try { if (liveRecorder != null) { liveRecorder.release(); liveRecorder = null; } } catch (Exception ignore) {}
    }
  }

  private void stopLiveTranscription() {
    isLive = false;
    if (liveThread != null) {
      try { liveThread.join(1000); } catch (InterruptedException ignored) {}
      liveThread = null;
    }
  }

  // Robust JSON extraction using org.json; falls back to empty string on error.
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
      print("JSON parse fail: " + trimForLog(json) + " err=" + e.getMessage());
      return "";
    }
  }

  private String trimForLog(String s) {
    if (s == null) return "null";
    if (s.length() > 160) return s.substring(0, 160) + "...";
    return s;
    }

  private float nowSec() {
    return (float) (System.nanoTime() / 1_000_000_000.0);
  }

  // Compute RMS in dBFS for byte PCM16 (little-endian). Overload for recordedData[] (byte[]) and buf (byte[]).
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
    // 16-bit full-scale is 32768
    double db = 20.0 * Math.log10(rms / 32768.0 + 1e-12);
    return (float) db;
  }

  private void resetBuffer() {
    synchronized (lock) {
      recordedBytes    = 0;
      playbackPosition = 0;
    }
    print("BUFFER: Reset complete");
  }
}
