package myapp.app.utils;

import myapp.app.MainActivity;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModelDownloader extends Thread {
  public  volatile       boolean      done = false;
  public  static   final String       VOSK_MODEL_NAME = "vosk-model-en-us-0.22-lgraph";
  private static   final String       VOSK_MODEL_URL  = "https://alphacephei.com/vosk/models/" + VOSK_MODEL_NAME + ".zip";
  private final          MainActivity main;

  public ModelDownloader(MainActivity main) {
    this.main = main;
  }

  @Override
  public void run() {
    main.print("DOWNLOADER: Checking model files...");
    try {
      File modelDir = new File(main.getCacheDir(), VOSK_MODEL_NAME);
      if (!modelDir.exists()) {
        modelDir.mkdirs();
        File zipFile = new File(main.getCacheDir(), "vosk-model.zip");
        main.print("DOWNLOADER: Vosk model missing. Downloading...");
        downloadFile(VOSK_MODEL_URL, zipFile);
        unzip(zipFile, main.getCacheDir());
        zipFile.delete();
      } else {
        main.print("DOWNLOADER: Vosk model found.");
      }
    } catch (Exception e) {
      main.print("EXCEPTION: " + e.toString());
      pause(1.0f);
    } finally {
      done = true;
    }
  }

  private void downloadFile(String urlString, File outFile) throws IOException {
    URL               url  = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.connect();

    int code = conn.getResponseCode();
    if (code != HttpURLConnection.HTTP_OK) {
      main.print("Server did not returned HTTP_OK!");
      main.print("responseCode = " + code);
      return;
    }

    InputStream      input  = new BufferedInputStream(conn.getInputStream());
    FileOutputStream output = new FileOutputStream   (outFile);

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

    output.flush     ();
    output.close     ();
    input .close     ();
    conn  .disconnect();

    main.print("DOWNLOADER: Finished downloading " + outFile.getName());
  }

  private void unzip(File zipFile, File targetDir) throws IOException {
    ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
    ZipEntry ze;
    byte[] buffer = new byte[1024];
    while ((ze = zis.getNextEntry()) != null) {
      File newFile = new File(targetDir, ze.getName());
      if (ze.isDirectory()) {
        newFile.mkdirs();
        continue;
      }
      new File(newFile.getParent()).mkdirs();
      FileOutputStream fos = new FileOutputStream(newFile);
      int len;
      while ((len = zis.read(buffer)) > 0) {
        fos.write(buffer, 0, len);
      }
      fos.close();
    }
    zis.closeEntry();
    zis.close();
  }

  public void pause(float seconds) {
    try {
      Thread.sleep((long)(seconds * 1000));
    } catch (InterruptedException e) {
      main.print("InterruptedException: " + e.toString());
    }
  }
}