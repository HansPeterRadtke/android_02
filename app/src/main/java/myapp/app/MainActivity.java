package myapp.app;
import myapp.app.utils.ModelDownloader;
import myapp.app.utils.WhisperRunner  ;

import android.Manifest;
import android.app.Activity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import androidx.core.app.ActivityCompat;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
  private TextView      statusText  ;
  private Button        recordButton;
  private Button        playButton  ;
  private AudioRecord   recorder    ;
  private AudioTrack    player      ;
  private boolean       isRecording  = false;
  private boolean       isPlaying    = false;
  private int           sampleRate   = 16000;
  private final byte[]  recordedData     = new byte[sampleRate * 2 * 60 * 15];
  private int           recordedBytes    = 0;
  private int           playbackPosition = 0;
  private final Object  lock             = new Object();
  private WhisperRunner whisperRunner;

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
    playButton.setText("Read Text");
    layout.addView(playButton);

    Button toTextButton = new Button(this);
    toTextButton.setText("To Text");
    layout.addView(toTextButton);

    statusText = new TextView(this);
    statusText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    statusText.setTextIsSelectable(true);
    statusText.setSingleLine(false);
    statusText.setMaxLines(Integer.MAX_VALUE);
    ScrollView scrollView = new ScrollView(this);
    scrollView.setFillViewport(true);
    scrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    scrollView.addView(statusText);
    layout.addView(scrollView);

    setContentView(layout);

    print("APP: onCreate() called. App initialized.");

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

    toTextButton.setOnClickListener(v -> {
      print("Running whisper now");
      print(this.whisperRunner.transcribe(this.recordedData, recordedBytes));
      print("BUTTON: To Text pressed. Buffer reset requested.");
      resetBuffer();
    });

    this.whisperRunner = new WhisperRunner  (this);
    ModelDownloader md = new ModelDownloader(this);

    md.start();
  }

  public void print(String msg) {
    runOnUiThread(() -> { statusText.append(msg + "\n"); statusText.invalidate(); });
  }

  private void startRecording() {
    try {
      print("RECORD: Start recording initiated.");
      recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
          AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT));
      recorder.startRecording();
      isRecording = true;
      recordButton.setText("Stop Recording");

      new Thread(() -> {
        int offset;
        synchronized (lock) {
          offset = recordedBytes;
        }
        int chunkSize = 8192;
        while (isRecording && offset < recordedData.length) {
          int read = recorder.read(recordedData, offset, Math.min(chunkSize, recordedData.length - offset));
          if (read > 0) {
            offset += read;
            synchronized (lock) {
              recordedBytes = offset;
            }
            float seconds = (float) offset / (sampleRate * 2);
            String status = String.format("RECORD: Bytes: %d | Duration: %.2f sec", offset, seconds);
            print(status);
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
      print("RECORD: Stop recording requested.");
      isRecording = false;
      if (recorder != null) {
        recorder.stop();
        recorder.release();
        recorder = null;
      }
    } catch (Exception e) {
      print("EXCEPTION: " + e.toString());
    }
  }

  private void startPlayback() {
    try {
      print("PLAY: Start playback requested.");
      synchronized (lock) {
        if (playbackPosition >= recordedBytes) playbackPosition = 0;
      }
      int bufferSize = AudioTrack.getMinBufferSize(sampleRate,
          AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      player = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
          AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
          bufferSize, AudioTrack.MODE_STREAM);
      player.play();
      isPlaying = true;
      playButton.setText("Stop Playback");

      new Thread(() -> {
        int chunkSize = 1024;
        int localPosition;
        synchronized (lock) {
          localPosition = playbackPosition;
        }
        while (isPlaying) {
          int toWrite;
          int recorded;
          synchronized (lock) {
            toWrite = Math.min(chunkSize, recordedBytes - localPosition);
            recorded = recordedBytes;
          }
          if (toWrite <= 0) break;
          player.write(recordedData, localPosition, toWrite);
          localPosition += toWrite;
          synchronized (lock) {
            playbackPosition = localPosition;
          }
          float seconds = (float) recorded / (sampleRate * 2);
          String status = String.format("PLAY: Bytes: %d | Duration: %.2f sec | Pos: %d", recorded, seconds, localPosition);
          runOnUiThread(() -> print(status));
        }
        runOnUiThread(this::stopPlayback);
      }).start();
    } catch (Exception e) {
      print("EXCEPTION: " + e.toString());
    }
  }

  private void stopPlayback() {
    try {
      print("PLAY: Stop playback requested.");
      isPlaying = false;
      if (player != null) {
        player.stop();
        player.release();
        player = null;
      }
      playButton.setText("Read Text");
    } catch (Exception e) {
      runOnUiThread(() -> print("EXCEPTION: " + e.toString()));
    }
  }

  private void resetBuffer() {
    synchronized (lock) {
      recordedBytes = 0;
      playbackPosition = 0;
    }
    print("BUFFER: Reset complete");
  }
}
