package myapp.app;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

public class TTS {

  private static final String TAG = "TTS";
  private TextToSpeech tts;
  private boolean ttsReady = false;
  private MainActivity mainActivity;

  public TTS(Context context) {
    try {
      if (context instanceof MainActivity) {
        mainActivity = (MainActivity) context;
        mainActivity.print("(TTS constructor) Context is MainActivity");
      }
      printAndLog("(constructor) Starting TTS initialization with SherpaTTS package");
      tts = new TextToSpeech(context, status -> {
        printAndLog("(constructor) onInit called with status: " + status);
        if (status == TextToSpeech.SUCCESS) {
          ttsReady = true;
          String engine = tts.getDefaultEngine();
          printAndLog("(constructor) SUCCESS: Using engine: " + engine);
          int result = tts.setLanguage(Locale.getDefault());
          printAndLog("(constructor) setLanguage(Locale.getDefault()) result: " + result);
          if (result == TextToSpeech.LANG_MISSING_DATA) {
            printAndLog("(constructor) Language missing data");
          }
          if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
            printAndLog("(constructor) Language not supported");
          }
        } else {
          printAndLog("(constructor) Initialization failed with status: " + status);
        }
      }, "org.woheller69.ttsengine");

      tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
          printAndLog("(UtteranceProgressListener) onStart: " + utteranceId);
        }

        @Override
        public void onDone(String utteranceId) {
          printAndLog("(UtteranceProgressListener) onDone: " + utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
          printAndLog("(UtteranceProgressListener) onError: " + utteranceId);
        }
      });

    } catch (Exception e) {
      printAndLog("(constructor) Exception while initializing TextToSpeech: " + e);
    }
  }

  public void speak(String text) {
    try {
      printAndLog("(speak) Called with text: " + text);
      if (tts == null) {
        printAndLog("(speak) ERROR: tts is null");
        return;
      }
      if (!ttsReady) {
        printAndLog("(speak) ERROR: TTS not ready yet");
        return;
      }
      Bundle params = new Bundle();
      int speakResult = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_utterance");
      printAndLog("(speak) speak() returned: " + speakResult);
    } catch (Exception e) {
      printAndLog("(speak) Exception while speaking: " + e);
    }
  }

  public void shutdown() {
    try {
      printAndLog("(shutdown) Called");
      if (tts != null) {
        tts.stop();
        tts.shutdown();
        printAndLog("(shutdown) TTS stopped and shutdown successfully");
      } else {
        printAndLog("(shutdown) ERROR: tts is null");
      }
    } catch (Exception e) {
      printAndLog("(shutdown) Exception while shutting down TTS: " + e);
    }
  }

  private void printAndLog(String msg) {
    Log.d(TAG, msg);
    if (mainActivity != null) {
      mainActivity.print(msg);
    }
  }
}