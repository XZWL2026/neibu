package com.xzwl.cloud.storage.application.file;

import com.xzwl.cloud.storage.domain.file.FilePurpose;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Inspects local bytes only; no remote URL or shell is ever passed to ffprobe. */
final class ReferenceMediaProbe {
  static final long VIDEO_MAX_BYTES = 200L * 1024 * 1024;
  static final long AUDIO_MAX_BYTES = 15L * 1024 * 1024;
  static final long IMAGE_MAX_BYTES = 20L * 1024 * 1024;
  private static final int OUTPUT_LIMIT = 16 * 1024;

  record Metadata(String mimeType, FilePurpose purpose, Double durationSeconds,
      Integer width, Integer height, Double fps) {}

  static String normalizeMime(String mime) {
    return switch (mime == null ? "" : mime) {
      case "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "audio/wav";
      case "audio/mp3" -> "audio/mpeg";
      default -> mime == null ? "" : mime;
    };
  }

  static long maxBytes(String mime) {
    return switch (normalizeMime(mime)) {
      case "image/png", "image/jpeg" -> IMAGE_MAX_BYTES;
      case "video/mp4", "video/quicktime" -> VIDEO_MAX_BYTES;
      case "audio/mpeg", "audio/wav" -> AUDIO_MAX_BYTES;
      default -> throw new FileValidationException("reference media must be JPG, PNG, MP4, MOV, MP3 or WAV");
    };
  }

  static Metadata inspect(Path path, String declaredMime) throws IOException {
    return inspect(path,declaredMime,false);
  }

  static Metadata inspect(Path path,String declaredMime,boolean processing) throws IOException {
    String mime = normalizeMime(declaredMime);
    if(processing && !mime.startsWith("video/")) throw new FileValidationException("processing requires video");
    byte[] signature;
    try (var input = Files.newInputStream(path)) { signature = input.readNBytes(12); }
    if (mime.startsWith("image/")) return image(path, mime);
    boolean mp4 = signature.length >= 12 && signature[4] == 'f' && signature[5] == 't'
        && signature[6] == 'y' && signature[7] == 'p';
    boolean mov = mp4 && signature[8] == 'q' && signature[9] == 't';
    // Browser MIME follows the extension; processing accepts either verified MP4/MOV container.
    if (processing && mp4 && (mime.equals("video/mp4") || mime.equals("video/quicktime")))
      mime = mov ? "video/quicktime" : "video/mp4";
    boolean wav = signature.length >= 12 && new String(signature, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        && new String(signature, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
    boolean mp3 = signature.length >= 3 && ((signature[0] == 'I' && signature[1] == 'D' && signature[2] == '3')
        || ((signature[0] & 0xff) == 0xff && (signature[1] & 0xe0) == 0xe0));
    boolean matches = switch (mime) {
      case "video/mp4" -> mp4 && !mov;
      case "video/quicktime" -> mov;
      case "audio/wav" -> wav;
      case "audio/mpeg" -> mp3;
      default -> false;
    };
    if (!matches) throw new FileValidationException("declared MIME does not match file signature");
    boolean video = mime.startsWith("video/");
    Probe probe = probe(path);
    String format = probe.format().getOrDefault("format_name", "");
    if (!(video ? format.contains("mov") : format.equals(wav ? "wav" : "mp3")))
      throw new FileValidationException("media container does not match the upload");
    Map<String, String> stream = probe.streams().stream()
        .filter(item -> item.getOrDefault("codec_type", "").equals(video ? "video" : "audio"))
        .findFirst().orElseThrow(() -> new FileValidationException("reference media stream is unavailable"));
    if (video) {
      for (var track : probe.streams()) {
        String type = track.getOrDefault("codec_type", ""), codec = track.getOrDefault("codec_name", "");
        if ((type.equals("video") && !codec.equals("h264") && !codec.equals("hevc"))
            || (type.equals("audio") && !codec.equals("aac") && !codec.equals("mp3"))) {
          throw new FileValidationException("reference video requires H.264/H.265 video and AAC/MP3 audio");
        }
      }
    }
    double duration = number(probe.format().get("duration"));
    String streamDuration = stream.get("duration");
    if (streamDuration != null && !streamDuration.equals("N/A")) duration = Math.max(duration, number(streamDuration));
    if(processing) {
      duration=number(streamDuration); // Video track only: trailing audio is deliberately excluded.
      if(duration<=0) throw new FileValidationException("video duration is unavailable");
    }
    if (!processing && (duration < 2 || duration > 30)) throw new FileValidationException("reference media duration must be 2–30 seconds");
    if (!video) return new Metadata(mime, FilePurpose.REFERENCE_AUDIO, duration, null, null, null);
    int width = (int) number(stream.get("width"));
    int height = (int) number(stream.get("height"));
    if(processing) {
      if(width<=0 || height<=0 || width>8192 || height>8192) throw new FileValidationException("video dimensions are outside decoder limits");
    } else dimensions(width, height);
    long pixels = (long) width * height;
    if (!processing && (pixels < 407696 || pixels > 8295044)) throw new FileValidationException("reference video pixel count is outside the allowed range");
    String[] rate = stream.getOrDefault("avg_frame_rate", "").split("/");
    double fps = rate.length == 2 ? number(rate[0]) / number(rate[1]) : number(rate[0]);
    if (!Double.isFinite(fps) || fps<=0 || (!processing && (fps < 24 || fps > 60))) throw new FileValidationException("reference video frame rate must be 24–60 fps");
    return new Metadata(mime, processing?FilePurpose.PROCESSING_VIDEO:FilePurpose.REFERENCE_VIDEO, duration, width, height, fps);
  }

  private static Metadata image(Path path, String mime) throws IOException {
    try (var input = ImageIO.createImageInputStream(path.toFile())) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new FileValidationException("invalid reference image");
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        String format = reader.getFormatName();
        if (!(mime.equals("image/jpeg") && format.equalsIgnoreCase("JPEG"))
            && !(mime.equals("image/png") && format.equalsIgnoreCase("PNG"))) {
          throw new FileValidationException("declared MIME does not match file signature");
        }
        int width = reader.getWidth(0), height = reader.getHeight(0);
        dimensions(width, height);
        // Decode after bounding dimensions so truncated image headers cannot become valid references.
        reader.read(0);
        return new Metadata(mime, FilePurpose.REFERENCE_IMAGE, null, width, height, null);
      } finally { reader.dispose(); }
    }
  }

  private static void dimensions(int width, int height) {
    double ratio = (double) width / height;
    if (width < 300 || height < 300 || width > 6000 || height > 6000 || ratio < 0.4 || ratio > 2.5)
      throw new FileValidationException("reference dimensions must be 300–6000 with aspect ratio 0.4–2.5");
  }

  private static double number(String value) {
    try {
      double result = Double.parseDouble(value);
      if (Double.isFinite(result)) return result;
    } catch (NullPointerException | NumberFormatException ignored) { }
    throw new FileValidationException("reference media metadata is unavailable");
  }

  record Probe(Map<String, String> format, List<Map<String, String>> streams) {}

  private static Probe probe(Path path) throws IOException {
    String executable = System.getenv().getOrDefault("STORAGE_FFPROBE_PATH", "ffprobe");
    return probe(path, executable, 10000);
  }

  static Probe probe(Path path, String executable, long timeoutMillis) throws IOException {
    Process process = new ProcessBuilder(executable, "-v", "error",
        "-protocol_whitelist", "file", "-format_whitelist", "mov,mp3,wav", "-threads", "1",
        "-show_entries", "stream=codec_name,codec_type,width,height,avg_frame_rate,duration:format=format_name,duration",
        "-of", "default=noprint_wrappers=0", path.toAbsolutePath().toString())
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    process.getOutputStream().close();
    AtomicReference<byte[]> output = new AtomicReference<>();
    AtomicReference<IOException> readFailure = new AtomicReference<>();
    Thread reader = Thread.startVirtualThread(() -> {
      try (var stdout = process.getInputStream()) {
        byte[] bytes = stdout.readNBytes(OUTPUT_LIMIT + 1);
        if (bytes.length > OUTPUT_LIMIT) process.destroyForcibly();
        output.set(bytes);
      } catch (IOException failure) { readFailure.set(failure); }
    });
    try {
      if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new FileValidationException("reference media inspection timed out");
      reader.join(1000);
      if (process.exitValue() != 0 || readFailure.get() != null || output.get() == null || output.get().length > OUTPUT_LIMIT)
        throw new FileValidationException("reference media could not be inspected");
      var format = new HashMap<String, String>();
      var streams = new ArrayList<Map<String, String>>();
      Map<String, String> section = null;
      for (String line : new String(output.get(), StandardCharsets.UTF_8).split("\\R")) {
        if (line.equals("[STREAM]")) { section = new HashMap<>(); streams.add(section); }
        else if (line.equals("[FORMAT]")) section = format;
        else if (line.startsWith("[")) section = null;
        else if (section != null) {
          int separator = line.indexOf('=');
          if (separator > 0) section.put(line.substring(0, separator), line.substring(separator + 1));
        }
      }
      return new Probe(format, streams);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new FileValidationException("reference media inspection interrupted", interrupted);
    } finally {
      process.destroyForcibly();
      try { process.waitFor(1, TimeUnit.SECONDS); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      process.getInputStream().close();
      process.getOutputStream().close();
      reader.interrupt();
    }
  }
}
