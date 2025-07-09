package myapp.app.utils;

import myapp.app.MainActivity;
import java.io.File;

public class Whisper {

  private final MainActivity main;
  private final String modelPath;

  public Whisper(MainActivity main, String filesDirPath) {
    this.main = main;
    this.modelPath = filesDirPath + "/models/ggml-tiny.en.bin";
    main.print("WHISPER: Constructor called");
  }

  public String transcribe(short[] audio) {
    main.print("WHISPER: Starting transcription");
    String result = WhisperService.fullTranscribe(audio, 16000, modelPath);
    main.print("TRANSCRIBED: \"" + result + "\"");
    return result;
  }
}