package myapp.app.utils;

import myapp.app.MainActivity;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class ModelDownloader extends Thread {
  private final MainActivity main;

  private static final String WHISPER_CPP_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/models/ggml-tiny.en.bin";

  public ModelDownloader(MainActivity main) {
    this.main = main;
  }

  @Override
  public void run() {
    main.print("DOWNLOADER: Checking model files...");
    try {
      File modelDir = new File(main.getFilesDir(), "models");
      if (!modelDir.exists()) {
        modelDir.mkdirs();
      }

      File whisperCppModel = new File(modelDir, "ggml-tiny.en.bin");
      if (!whisperCppModel.exists()) {
        main.print("DOWNLOADER: Whisper.cpp model missing. Downloading...");
        downloadFile(WHISPER_CPP_URL, whisperCppModel);
      } else {
        main.print("DOWNLOADER: Whisper.cpp model found.");
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
      main.print("responseCode = " + code);
      return;
    }

    InputStream input = new BufferedInputStream(conn.getInputStream());
    FileOutputStream output = new FileOutputStream(outFile);

    byte[] data = new byte[16384];
    int count;
    int i = 0;
    while ((count = input.read(data)) != -1) {
      output.write(data, 0, count);
      if ((i % 1000) == 0) {
        main.print("Downloaded and saved " + count + " bytes");
      }
      i++;
    }

    output.flush();
    output.close();
    input.close();
    conn.disconnect();

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