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

  private static final String WHISPER_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.onnx";
  private static final String VITS_URL    = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-ljs.tar.bz2";

  public ModelDownloader(MainActivity main) {
    this.main = main;
  }

  @Override
  public void run() {
    main.print("DOWNLOADER: Checking model files...");
    try {
      File whisperFile = new File(main.getFilesDir(), "whisper.onnx");
      File vitsFile    = new File(main.getFilesDir(), "vits.onnx"   );

      if (!whisperFile.exists()) {
        main.print("DOWNLOADER: Whisper model missing. Downloading...");
        downloadFile(WHISPER_URL, whisperFile);
      } else {
        main.print("DOWNLOADER: Whisper model already present.");
      }

      if (!vitsFile.exists()) {
        main.print("DOWNLOADER: VITS model missing. Downloading...");
        downloadFile(VITS_URL, vitsFile);
      } else {
        main.print("DOWNLOADER: VITS model already present.");
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

    byte[] data = new byte[4096];
    int count;
    int i = 0;
    while ((count = input.read(data)) != -1) {
      output.write(data, 0, count);
      if((i % 100) == 0) {
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
