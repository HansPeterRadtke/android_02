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

import java.io.File;

public class MainActivity extends Activity {
  public  String        model_name  = "vosk-model-en-us-0.22-lgraph";
  private TextView      statusText  ;
  private Button        recordButton;
  private Button        playButton  ;
  private Button        readTextButton;
  private AudioRecord   recorder    ;
  private AudioTrack    player      ;
  private boolean       isRecording  = false;
  private boolean       isPlaying    = false;
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

    Button toTextButton = new Button(this);
    toTextButton.setText("To Text");
    layout.addView(toTextButton);

    statusText = new TextView(this);
    statusText.setLayoutParams    (new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    statusText.setTextIsSelectable(true);
    statusText.setSingleLine      (false);
    statusText.setMaxLines        (Integer.MAX_VALUE);
    ScrollView scrollView = new ScrollView(this);
    scrollView.setFillViewport(true);
    scrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    scrollView.addView        (statusText);
    layout.addView(scrollView);

    setContentView(layout);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
      }
    }

    recordButton.setOnClickListener(v -> {
      if (!isRecording) {
        startRecording();
      } else {
        stopRecording();
      }
    });

    playButton.setOnClickListener(v -> {
      if (!isPlaying) {
        startPlayback();
      } else {
        stopPlayback();
      }
    });

    readTextButton.setOnClickListener(v -> {
      print("Read Text pressed");
    });

    toTextButton.setOnClickListener(v -> {
      try {
        float startTranscription = ((float)System.nanoTime() / 1_000_000_000f);
        synchronized (lock) {
          if (recordedBytes > 0) {
            recognizer.acceptWaveForm(recordedData, recordedBytes);
            print(recognizer.getFinalResult());
          } else {
            print("No recorded audio to process.");
          }
        }
        float endTranscription      = ((float)System.nanoTime() / 1_000_000_000f);
        float durationTranscription = (endTranscription - startTranscription);
        print("(toTextButton) Transcription took: " + durationTranscription + " seconds");
        float audioSeconds          = ((float) recordedBytes / (sampleRate * 2));
        print("(toTextButton) audioSeconds = " + audioSeconds + " seconds");
        if (audioSeconds > 0) {
          float factor = ((float)durationTranscription / audioSeconds);
          print("(toTextButton) Processing speed factor: " + factor + "x real-time");
        }
        resetBuffer();
      } catch (Exception e) {
        print("EXCEPTION: " + e.toString());
      }
    });

    print("(onCreate) Creating ModelDownloader");
    ModelDownloader md = new ModelDownloader(this);
    print("(onCreate) Starting ModelDownloader");
    md.run();
    while (!md.done) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        print("InterruptedException while waiting for model download");
      }
    }
    print("(onCreate) md.done == " + md.done);

    try {
      float startModel      = ((float)System.nanoTime() / 1_000_000_000f);
      print("(onCreate) Creating Model...");
      this.model            = new Model(getCacheDir() + "/vosk-model-en-us-0.22-lgraph");
      float endModel        = ((float)System.nanoTime() / 1_000_000_000f);
      print("(onCreate) Model creation took: " + (endModel - startModel) + " seconds");

      float startRecognizer = ((float)System.nanoTime() / 1_000_000_000f);
      this.recognizer       = new Recognizer(model, 16000.0f);
      float endRecognizer   = ((float)System.nanoTime() / 1_000_000_000f);
      print("(onCreate) Recognizer creation took: " + (endRecognizer - startRecognizer) + " seconds");
    } catch (Exception e) {
      print("EXCEPTION during model/recognizer init: " + e.toString());
    }
  }

  public void print(String msg) {
    synchronized (lock) {
      runOnUiThread(() -> { statusText.append(msg + "\n"); statusText.invalidate(); });
    }
  }

  private void startRecording() {
    try {
      recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
      recorder.startRecording();
      isRecording = true;
      recordButton.setText("Stop Recording");

      new Thread(() -> {
        int offset;
        synchronized (lock) {
          offset = recordedBytes;
        }
        int   chunkSize     = 8192;
        float lastPrintTime = (System.nanoTime() / 1_000_000_000f);
        while(isRecording && (offset < recordedData.length)) {
          int read = recorder.read(recordedData, offset, Math.min(chunkSize, (recordedData.length - offset)));
          if(read > 0) {
            offset += read;
            synchronized (lock) {
              recordedBytes = offset;
            }
            float seconds = ((float) offset / (sampleRate * 2));
            if(((System.nanoTime() / 1_000_000_000f) - lastPrintTime) >= 2.0) {
              String status = String.format("RECORD: Bytes: %d | Duration: %.2f sec", offset, seconds);
              print(status);
              lastPrintTime = (System.nanoTime() / 1_000_000_000f);
            }
          }
        }
        runOnUiThread(() -> recordButton.setText("Start Recording"));
      }).start();
    } catch (Exception e) {
      print("EXCEPTION: " + e.toString());
    }
  }

  private void stopRecording() {
    try {
      isRecording = false;
      if (recorder != null) {
        recorder.stop   ();
        recorder.release();
        recorder = null;
        float duration_sec = ((float) recordedBytes / (sampleRate * 2));
        print("(stopRecording) duration_sec = " + duration_sec);
      }
    } catch (Exception e) {
      print("EXCEPTION: " + e.toString());
    }
  }

  private void startPlayback() {
    try {
      synchronized (lock) {
        if (playbackPosition >= recordedBytes) playbackPosition = 0;
      }
      int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      player         = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
      player.play();
      isPlaying = true;
      playButton.setText("Stop Playback");

      new Thread(() -> {
        int chunkSize = 1024;
        int localPosition;
        synchronized (lock) {
          localPosition = playbackPosition;
        }
        float lastPrintTime = (System.nanoTime() / 1_000_000_000f);
        while(isPlaying) {
          int toWrite ;
          int recorded;
          synchronized (lock) {
            toWrite  = Math.min(chunkSize, (recordedBytes - localPosition));
            recorded = recordedBytes;
          }
          if(toWrite <= 0) break;
          player.write(recordedData, localPosition, toWrite);
          localPosition += toWrite;
          synchronized (lock) {
            playbackPosition = localPosition;
          }
          if(((System.nanoTime() / 1_000_000_000f) - lastPrintTime) >= 2.0) {
            float  seconds = (float) recorded / (sampleRate * 2);
            String status  = String.format("PLAY: Bytes: %d | Duration: %.2f sec | Pos: %d", recorded, seconds, localPosition);
            print(status);
            lastPrintTime  = (System.nanoTime() / 1_000_000_000f);
          }
        }
        runOnUiThread(this::stopPlayback);
      }).start();
    } catch (Exception e) {
      print("EXCEPTION: " + e.toString());
    }
  }

  private void stopPlayback() {
    try {
      isPlaying = false;
      if (player != null) {
        player.stop   ();
        player.release();
        player = null;
      }
      playButton.setText("Play Recorded Audio");
    } catch (Exception e) {
      runOnUiThread(() -> print("EXCEPTION: " + e.toString()));
    }
  }

  private void resetBuffer() {
    synchronized (lock) {
      recordedBytes    = 0;
      playbackPosition = 0;
    }
    print("BUFFER: Reset complete");
  }
}