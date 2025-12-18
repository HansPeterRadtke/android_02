package myapp.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import myapp.app.utils.ModelDownloader;

public class MainActivity extends Activity {

  private Button     recordButton  ;
  private Button     playButton    ;
  private Button     readTextButton;
  private Button     toTextButton  ;
  private Button     liveButton    ;
  private TextView   liveText      ;
  private ScrollView liveScroll    ;
  private TextView   statusText    ;
  private ScrollView statusScroll  ;

  private STT stt;
  private TTS tts;

  private static final int PERMISSION_REQUEST_CODE = 200;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);

    recordButton = new Button(this);
    recordButton.setText("Start Recording");
    layout      .addView(recordButton);

    playButton = new Button(this);
    playButton.setText("Play Recorded Audio");
    layout    .addView(playButton);

    readTextButton = new Button(this);
    readTextButton.setText("Read Text");
    layout        .addView(readTextButton);

    toTextButton = new Button(this);
    toTextButton.setText("To Text");
    layout      .addView(toTextButton);

    liveButton = new Button(this);
    liveButton.setText("Start Live Transcription");
    layout    .addView(liveButton);

    liveText = new TextView       (this);
    liveText  .setLayoutParams    (new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    liveText  .setTextIsSelectable(true);
    liveText  .setSingleLine      (false);
    liveText  .setMinLines        (1);
    liveText  .setMaxLines        (10);
    liveText  .setText            ("");
    liveScroll = new ScrollView   (this);
    liveScroll.setFillViewport    (true);
    liveScroll.setLayoutParams    (new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    liveScroll.addView            (liveText);
    layout.addView(liveScroll);

    statusText = new TextView       (this);
    statusText  .setLayoutParams    (new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    statusText  .setTextIsSelectable(true);
    statusText  .setSingleLine      (false);
    statusText  .setMaxLines        (Integer.MAX_VALUE);
    statusText  .setText            ("");
    statusScroll = new ScrollView   (this);
    statusScroll.setFillViewport    (true);
    statusScroll.setLayoutParams    (new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    statusScroll.addView            (statusText);
    layout.addView(statusScroll);

    setContentView(layout);


    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
      }
    }

    recordButton.setOnClickListener(v -> {
      if (!stt.isRecording()) stt.startRecording(); else stt.stopRecording();
    });

    playButton.setOnClickListener(v -> {
      if (!stt.isPlaying  ()) stt.startPlayback (); else stt.stopPlayback ();
    });

    readTextButton.setOnClickListener(v -> {
      print("Read Text button pressed â€” NEW VERSION");
      if (tts != null) {
        tts.speak("This is an example text being read out loud.");
      } else {
        print("TTS object is null â€” cannot speak");
      }
    });

    toTextButton.setOnClickListener(v -> {
      stt.toText();
    });

    liveButton.setOnClickListener(v -> {
      if (!stt.isLive()) stt.startLiveTranscription(); else stt.stopLiveTranscription();
    });

    recordButton  .setEnabled(false);
    playButton    .setEnabled(false);
    readTextButton.setEnabled(false);
    toTextButton  .setEnabled(false);
    liveButton    .setEnabled(false);

    new Thread(() -> {
      print("(onCreateThread) Creating ModelDownloader");
      ModelDownloader md = new ModelDownloader(this);
      print("(onCreateThread) Starting ModelDownloader");
      md.start();
      while (!md.done) { try { Thread.sleep(200); } catch (InterruptedException ignore) {} }
      print("(onCreateThread) Model download complete, creating Model and STT");
      try {
        /*
        java.io.File   modelDir = new java.io  .File (getFilesDir(), myapp.app.utils.ModelDownloader.VOSK_MODEL_NAME);
        print("(onCreateThread) modelDir created");
        org.vosk.Model model    = new org .vosk.Model(modelDir.getAbsolutePath());
        print("(onCreateThread) model created");
        stt = new STT(this);
        print("(onCreateThread) STT created");
        stt.setModel(model);
        print("(onCreateThread) STT.Model set");
        */
        java.io.File voiceFile = new java.io.File(getFilesDir(), "cmu_us_slt.flitevox");
        print("(onCreateThread) voiceFile created");
        if (voiceFile.exists()) {
          print("Voice file found at: " + voiceFile.getAbsolutePath() + " (size: " + voiceFile.length() + ")");
          try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
          tts = new TTS(this, voiceFile.getAbsolutePath());
        } else {
          print("Voice file missing at: " + voiceFile.getAbsolutePath());
          try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
        }
        tts = new TTS(this, "/sdcard/Android/data/myapp.app/files/models/kokoro.onnx");
        runOnUiThread(() -> {
//          recordButton  .setEnabled(true );
//          playButton    .setEnabled(true );
          readTextButton.setEnabled(true );
//          toTextButton  .setEnabled(true );
//          liveButton    .setEnabled(true );
        });
        print("(onCreateThread) DONE");
      } catch (Exception e) {
        print("EXCEPTION(onCreateThread) (Model load): " + e);
      }
    }).start();
    print("(onCreate) Thread started and DONE");
  }

  public void print(String msg) {
      runOnUiThread(() -> {
          statusText.append(msg + "\n");
          statusScroll.post(() -> statusScroll.fullScroll(ScrollView.FOCUS_DOWN));
          Log.d("main", msg);
      });
  }


  public void setLiveText(String text) {
    runOnUiThread(() -> {
      liveText.setText(text);
      liveScroll.post(() -> liveScroll.fullScroll(android.view.View.FOCUS_DOWN));
    });
  }

  public void setLiveButtonText(String text) {
    runOnUiThread(() -> liveButton.setText(text));
  }
}
