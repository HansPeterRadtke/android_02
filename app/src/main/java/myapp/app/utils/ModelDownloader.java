package myapp.app.utils;

import myapp.app.MainActivity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ModelDownloader extends Thread {
  private final MainActivity main;

  private static final String WHISPER_ENCODER_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.onnx";
  private static final String WHISPER_DECODER_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.onnx";
  private static final String WHISPER_TOKENS_URL  = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt"  ;
  private static final String VITS_URL            = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-ljs.tar.bz2"            ;

  public ModelDownloader(MainActivity main) {
    this.main = main;
  }

  @Override
  public void run() {
    main.print("DOWNLOADER: Checking model files...");
    try {
      File whisperEnc = new File(main.getFilesDir(), "whisper-encoder.onnx");
      File whisperDec = new File(main.getFilesDir(), "whisper-decoder.onnx");
      File whisperTok = new File(main.getFilesDir(), "whisper-tokens.txt"  );
      File vitsFile   = new File(main.getFilesDir(), "vits.onnx"           );

      if (!whisperEnc.exists()) {
        main.print("DOWNLOADER: Whisper encoder missing. Downloading...");
        downloadFile(WHISPER_ENCODER_URL, whisperEnc);
      } else {
        main.print("DOWNLOADER: Whisper encoder found.");
      }

      if (!whisperDec.exists()) {
        main.print("DOWNLOADER: Whisper decoder missing. Downloading...");
        downloadFile(WHISPER_DECODER_URL, whisperDec);
      } else {
        main.print("DOWNLOADER: Whisper decoder found.");
      }

      if (!whisperTok.exists()) {
        main.print("DOWNLOADER: Whisper tokens missing. Downloading...");
        downloadFile(WHISPER_TOKENS_URL, whisperTok);
      } else {
        main.print("DOWNLOADER: Whisper tokens found.");
      }

      if (!vitsFile.exists()) {
        main.print("DOWNLOADER: VITS model missing. Downloading...");
        downloadFile(VITS_URL, vitsFile);
      } else {
        main.print("DOWNLOADER: VITS model found.");
      }
    } catch (Exception e) {
      main.print("EXCEPTION: " + e.toString());
      pause(1000);
    }
  }

  private void downloadFile(String urlString, File outFile) throws IOException {
    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.connect();

    int code = conn.getResponseCode();
    if (code != HttpURLConnection.HTTP_OK) {
      main.print("Server did not returned HTTP_OK!");
      main.print("responseCode = " + String.valueOf(code));
      return;
    }

    InputStream      input  = new BufferedInputStream(conn.getInputStream());
    FileOutputStream output = new FileOutputStream   (outFile);

    byte[] data = new byte[16384];
    int count;
    int i = 0;
    while ((count = input.read(data)) != -1) {
      output.write(data, 0, count);
      if((i % 1000) == 0) {
        main.print("Downloaded and saved " + count + " bytes; ");
      }
      i++;
    }

    output.flush();
    output.close();
    input .close();
    conn  .disconnect();

    main.print("DOWNLOADER: Finished downloading " + outFile.getName());
  }

  public void pause(int ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      main.print("InterruptedException: " + e.toString());
    }
  }
}
