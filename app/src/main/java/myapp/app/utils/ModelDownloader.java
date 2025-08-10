package myapp.app.utils;

import myapp.app.MainActivity;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModelDownloader extends Thread {
  public  volatile       boolean      done = false;
//  public  static   final String       VOSK_MODEL_NAME = "vosk-model-en-us-0.22-lgraph";
//  public  static   final String       VOSK_MODEL_NAME = "vosk-model-small-en-us-0.15";
  public  static   final String       VOSK_MODEL_NAME = "vosk-model-en-us-0.22";
  private static   final String       VOSK_MODEL_URL  = "https://alphacephei.com/vosk/models/" + VOSK_MODEL_NAME + ".zip";

  private static   final int          CONNECT_TIMEOUT_MS = 60_000;
  private static   final int          READ_TIMEOUT_MS    = 60_000;
  private static   final int          BUFFER_SIZE        = 64 * 1024;
  private static   final long         MIN_ZIP_BYTES      = 10_000L; // sanity check

  private final          MainActivity main;

  public ModelDownloader(MainActivity main) {
    this.main = main;
  }

  @Override
  public void run() {
    main.print("DOWNLOADER: Checking model files...");
    File filesRoot = main.getFilesDir(); // avoid cache eviction and size limits
    File modelDir  = new File(filesRoot, VOSK_MODEL_NAME);
    File tempDir   = new File(filesRoot, VOSK_MODEL_NAME + ".tmp");
    File zipFile   = new File(filesRoot, VOSK_MODEL_NAME + ".zip");

    try {
      if (modelDir.isDirectory()) {
        main.print("DOWNLOADER: Vosk model found.");
        return;
      }

      // Clean any previous temp leftovers
      if (tempDir.exists()) deleteRecursive(tempDir);
      if (zipFile.exists()) zipFile.delete();

      // Basic space check (model + unzip)
      long needBytes = (3L * 1024L * 1024L * 1024L); // ~3 GB headroom for large models
      long freeBytes = filesRoot.getUsableSpace();
      if (freeBytes < needBytes) {
        main.print("DOWNLOADER: Low space. Free=" + freeBytes + " need>=" + needBytes);
      }

      // Download
      main   .print("DOWNLOADER: Vosk model missing. Downloading...");
      tempDir.mkdirs();
      downloadFile(VOSK_MODEL_URL, zipFile);
      if (!zipFile.isFile() || zipFile.length() < MIN_ZIP_BYTES) {
        throw new IOException("Zip missing or too small: " + zipFile.getAbsolutePath());
      }

      // Unzip to temp
      main.print("DOWNLOADER: Unzipping " + zipFile.getName());
      unzip(zipFile, tempDir);
      zipFile.delete();

      // Determine actual extracted root
      File extracted    = new File(tempDir, VOSK_MODEL_NAME);
      File sourceToMove = extracted.isDirectory() ? extracted : tempDir;

      // Move into final location atomically if possible
      if (sourceToMove.equals(modelDir)) {
        // already correct
      } else if (!sourceToMove.renameTo(modelDir)) {
        // Fallback to copy if rename fails (e.g., cross-volume edge cases)
        copyDirectory(sourceToMove, modelDir);
        deleteRecursive(sourceToMove);
      }

      // Cleanup any temp leftovers
      if (tempDir.exists() && !tempDir.equals(modelDir)) deleteRecursive(tempDir);

      main.print("DOWNLOADER: Model ready at " + modelDir.getAbsolutePath());
    } catch (Exception e) {
      main.print("EXCEPTION: " + e.toString());
      // cleanup on failure; do not leave empty final dir
      if (tempDir .exists()) deleteRecursive(tempDir);
      if (zipFile .exists()) zipFile.delete();
      if (modelDir.exists() && modelDir.listFiles() != null && modelDir.listFiles().length == 0) {
        // remove empty final dir if accidentally created elsewhere
        // (we never create it here before success, but keep this for safety)
        modelDir.delete();
      }
      pause(1.0f);
    } finally {
      done = true;
    }
  }

  private void downloadFile(String urlString, File outFile) throws IOException {
    String currentUrl = urlString;
    int redirects = 0;
    while (true) {
      URL url = new URL(currentUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setRequestProperty("Accept-Encoding", "identity");
      conn.connect();

      int code = conn.getResponseCode();

      if (code / 100 == 3) {
        if (redirects++ > 10) {
          conn.disconnect();
          throw new IOException("Too many redirects for " + urlString);
        }
        String loc = conn.getHeaderField("Location");
        if (loc == null || loc.isEmpty()) {
          conn.disconnect();
          throw new IOException("Redirect without Location for " + urlString);
        }
        // Resolve relative redirects
        URL newUrl = new URL(url, loc);
        currentUrl = newUrl.toString();
        conn.disconnect();
        continue;
      }

      if (code != HttpURLConnection.HTTP_OK) {
        InputStream err = null;
        try { err = conn.getErrorStream(); } catch (Throwable ignored) {}
        if (err != null) { try { err.close(); } catch (Throwable ignored) {} }
        conn.disconnect();
        throw new IOException("HTTP " + code + " for " + urlString);
      }

      long contentLength = conn.getContentLengthLong();
      long totalRead     = 0;
      long nextLog       = 0;

      // Ensure parent dir exists
      File parent = outFile.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();

      try (InputStream in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
           OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER_SIZE)) {

        byte[] buf = new byte[BUFFER_SIZE];
        int r;
        while ((r = in.read(buf)) != -1) {
          out.write(buf, 0, r);
          totalRead += r;

          // Progress logging: every +1% or every +8 MB when length unknown
          if (contentLength > 0) {
            long percent = (totalRead * 100) / contentLength;
            if (percent >= nextLog) {
              main.print("DOWNLOADER: " + percent + "% (" + (totalRead / (1024 * 1024)) + " MiB)");
              nextLog = percent + 5; // log every ~5%
            }
          } else if (totalRead - nextLog >= (8L << 20)) {
            main.print("DOWNLOADER: " + (totalRead / (1024 * 1024)) + " MiB");
            nextLog = totalRead;
          }
        }
        out.flush();
      } finally {
        conn.disconnect();
      }

      main.print("DOWNLOADER: Finished downloading " + outFile.getName() + " (" + (totalRead / (1024 * 1024)) + " MiB)");
      return;
    }
  }

  private void unzip(File zipFile, File targetDir) throws IOException {
    if (!targetDir.exists()) targetDir.mkdirs();
    byte[] buffer = new byte[BUFFER_SIZE];

    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile), BUFFER_SIZE))) {
      ZipEntry ze;
      while ((ze = zis.getNextEntry()) != null) {
        File newFile = new File(targetDir, ze.getName());

        // ZipSlip protection
        String targetPath = targetDir.getCanonicalPath();
        String newPath    = newFile  .getCanonicalPath();
        if (!newPath.startsWith(targetPath + File.separator) && !newPath.equals(targetPath)) {
          zis.closeEntry();
          throw new IOException("Blocked Zip-Slip entry: " + ze.getName());
        }

        if (ze.isDirectory()) {
          if (!newFile.exists() && !newFile.mkdirs()) {
            throw new IOException("Failed to create dir: " + newFile);
          }
          zis.closeEntry();
          continue;
        }

        File parent = newFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
          throw new IOException("Failed to create parent dir: " + parent);
        }

        try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(newFile), BUFFER_SIZE)) {
          int len;
          while ((len = zis.read(buffer)) > 0) {
            fos.write(buffer, 0, len);
          }
          fos.flush();
        }
        zis.closeEntry();
      }
    }
  }

  public void pause(float seconds) {
    try {
      Thread.sleep((long)(seconds * 1000));
    } catch (InterruptedException e) {
      main.print("InterruptedException: " + e.toString());
      Thread.currentThread().interrupt();
    }
  }

  // Helpers

  private void deleteRecursive(File f) {
    if (f == null || !f.exists()) return;
    if (f.isDirectory()) {
      File[] files = f.listFiles();
      if (files != null) {
        for (File c : files) deleteRecursive(c);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }

  private void copyDirectory(File src, File dst) throws IOException {
    if (src.isDirectory()) {
      if (!dst.exists() && !dst.mkdirs()) {
        throw new IOException("Failed to create dir: " + dst);
      }
      File[] children = src.listFiles();
      if (children != null) {
        for (File c : children) {
          copyDirectory(new File(src, c.getName()), new File(dst, c.getName()));
        }
      }
    } else {
      try (InputStream in = new BufferedInputStream(new FileInputStream(src), BUFFER_SIZE);
           OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), BUFFER_SIZE)) {
        byte[] buf = new byte[BUFFER_SIZE];
        int r;
        while ((r = in.read(buf)) != -1) {
          out.write(buf, 0, r);
        }
        out.flush();
      }
    }
  }
}
