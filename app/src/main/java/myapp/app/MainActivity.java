package myapp.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
  private TextView    statusText  ;
  private Button      recordButton;
  private Button      playButton  ;
  private AudioRecord recorder    ;
  private AudioTrack  player      ;
  private boolean      isRecording = false;
  private boolean      isPlaying   = false;
  private int          sampleRate  = 16000;
  private final byte[] recordedData     = new byte[sampleRate * 2 * 60 * 15];
  private int 	       recordedBytes    = 0;
  private int 	       playbackPosition = 0;
  private final Object lock = new Object();
  private Thread  uiUpdateThread;
  private boolean uiUpdateRunning = true;

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
    ScrollView scrollView = new ScrollView(this);
    scrollView.addView(statusText);
    layout.addView(scrollView);

    setContentView(layout);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
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

    toTextButton.setOnClickListener(v -> resetBuffer());

    startUIUpdateThread();
  }

  private void startUIUpdateThread() {
    uiUpdateThread = new Thread(() -> {
      try {
        while (uiUpdateRunning) {
          Thread.sleep(50);
          runOnUiThread(this::updateStatus);
        }
      } catch (InterruptedException ignored) {}
    });
    uiUpdateThread.start();
  }

  private void updateStatus() {
    int bytes;
    int pos;
    synchronized (lock) {
      bytes = recordedBytes   ;
      pos   = playbackPosition;
    }
    float seconds = (float) bytes / (sampleRate * 2);
    String status = String.format("Bytes: %d\nDuration: %.2f sec\nPlayback Pos: %d", bytes, seconds, pos);
    statusText.setText(status);
    statusText.invalidate();
  }

  private void startRecording() {
    try {
      recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
      recorder.startRecording();
      isRecording = true;
      recordButton.setText("Stop Recording");

      new Thread(() -> {
	int offset = 0;
        int chunkSize = 8192;
        while (isRecording && offset < recordedData.length) {
          int read = recorder.read(recordedData, offset, Math.min(chunkSize, recordedData.length - offset));
          if (read > 0) {
            offset += read;
            synchronized (lock) {
              recordedBytes = offset;
            }
          }
        }
        runOnUiThread(() -> recordButton.setText("Start Recording"));
      }).start();
    } catch (Exception e) {
      updateStatus();
    }
  }

  private void stopRecording() {
    try {
      isRecording = false;
      if (recorder != null) {
        recorder.stop   ();
        recorder.release();
        recorder = null;
      }
    } catch (Exception e) {
      updateStatus();
    }
  }

  private void startPlayback() {
    try {
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
          synchronized (lock) {
            toWrite = Math.min(chunkSize, recordedBytes - localPosition);
          }
          if (toWrite <= 0) break;
          player.write(recordedData, localPosition, toWrite);
          localPosition += toWrite;
          synchronized (lock) {
            playbackPosition = localPosition;
          }
        }
        runOnUiThread(this::stopPlayback);
      }).start();
    } catch (Exception e) {
      updateStatus();
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
      playButton.setText("Read Text");
    } catch (Exception e) {
      updateStatus();
    }
  }

  private void resetBuffer() {
    synchronized (lock) {
      recordedBytes    = 0;
      playbackPosition = 0;
    }
    updateStatus();
  }
}
