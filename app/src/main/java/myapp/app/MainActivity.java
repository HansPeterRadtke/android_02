package myapp.app;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
  private static final int PERMISSION_REQUEST_CODE = 200;

  private AudioRecord recorder;
  private AudioTrack player;
  private Thread recordingThread;
  private boolean isRecording = false;
  private boolean isPlaying = false;
  private byte[] recordedData;
  private int recordedBytes = 0;
  private Button recordButton;
  private Button readTextButton;
  private TextView errorView;
  private long recordingStartTime;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);

    TextView t = new TextView(this);
    t.setText("Hello World");
    layout.addView(t);

    Button exitButton = new Button(this);
    exitButton.setText("Exit");
    exitButton.setOnClickListener(v -> {
      stopRecording();
      stopPlayback();
      finishAndRemoveTask();
      Process.killProcess(Process.myPid());
    });
    layout.addView(exitButton);

    recordButton = new Button(this);
    recordButton.setText("Record");
    recordButton.setOnClickListener(v -> {
      try {
        if (!checkPermission()) {
          requestPermission();
          return;
        }
        stopPlayback();
        if (isRecording) {
          stopRecording();
          recordButton.setText("Record");
        } else {
          startRecording();
          recordButton.setText("Stop Recording");
        }
        showStatus();
      } catch (Exception e) {
        showError(e);
      }
    });
    layout.addView(recordButton);

    Button toTextButton = new Button(this);
    toTextButton.setText("To Text");
    toTextButton.setOnClickListener(v -> {
      try {
        stopRecording();
        stopPlayback();
        recordButton.setText("Record");
        readTextButton.setText("Read Text");
        showStatus();
      } catch (Exception e) {
        showError(e);
      }
    });
    layout.addView(toTextButton);

    readTextButton = new Button(this);
    readTextButton.setText("Read Text");
    readTextButton.setOnClickListener(v -> {
      try {
        stopRecording();
        recordButton.setText("Record");
        if (isPlaying) {
          stopPlayback();
          readTextButton.setText("Read Text");
        } else {
          if (recordedData != null && recordedBytes > 0) {
            playAudio(recordedData, recordedBytes);
            readTextButton.setText("Stop Playback");
          }
        }
        showStatus();
      } catch (Exception e) {
        showError(e);
      }
    });
    layout.addView(readTextButton);

    errorView = new TextView(this);
    layout.addView(errorView);

    setContentView(layout);
  }

  private boolean checkPermission() {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
  }

  private void requestPermission() {
    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSION_REQUEST_CODE) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        errorView.setText("Permission granted. Please press Record again.");
      } else {
        errorView.setText("Permission denied.");
      }
    }
  }

  private void showError(Exception e) {
    if (errorView != null) {
      errorView.setText("Error: " + e.getMessage());
    }
  }

  private void showStatus() {
    if (errorView != null) {
      int sampleRate = 16000;
      float seconds = (float) recordedBytes / (sampleRate * 2);
      errorView.setText("Recorded bytes: " + recordedBytes + "\nSeconds: " + seconds);
    }
  }

  private void startRecording() {
    int sampleRate = 16000;
    int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT);

    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize);

    recordedData = new byte[sampleRate * 2 * 5];
    recordedBytes = 0;
    recorder.startRecording();
    isRecording = true;
    recordingStartTime = System.currentTimeMillis();

    recordingThread = new Thread(() -> {
      int offset = 0;
      while (isRecording && offset < recordedData.length) {
        int read = recorder.read(recordedData, offset, recordedData.length - offset);
        if (read > 0) {
          offset += read;
        }
      }
      recordedBytes = offset;
    });
    recordingThread.start();
  }

  private void stopRecording() {
    try {
      if (recorder != null) {
        isRecording = false;
        recorder.stop();
        recorder.release();
        recorder = null;
      }
    } catch (Exception e) {
      showError(e);
    }
  }

  private void playAudio(byte[] data, int length) {
    try {
      int sampleRate = 16000;
      int bufferSize = AudioTrack.getMinBufferSize(sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT);

      player = new AudioTrack(AudioManager.STREAM_MUSIC,
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
        AudioTrack.MODE_STREAM);

      player.play();
      isPlaying = true;
      new Thread(() -> {
        player.write(data, 0, length);
        stopPlayback();
        runOnUiThread(() -> readTextButton.setText("Read Text"));
        showStatus();
      }).start();
    } catch (Exception e) {
      showError(e);
    }
  }

  private void stopPlayback() {
    try {
      if (player != null && isPlaying) {
        player.stop();
        player.release();
        player = null;
        isPlaying = false;
      }
    } catch (Exception e) {
      showError(e);
    }
  }
}