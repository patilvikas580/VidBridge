package com.YTDOWNLOADER.V.controller;

import com.YTDOWNLOADER.V.Service.VideoDownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    @Autowired
    private VideoDownloadService service;

    private static final String DOWNLOAD_DIR = "C:/downloads/";
    private static final String YT_DLP_PATH = "C:/Users/patil/AppData/Local/Microsoft/WinGet/Packages/yt-dlp.yt-dlp_Microsoft.Winget.Source_8wekyb3d8bbwe/yt-dlp.exe";
    private static final String FFMPEG_PATH = "C:/Users/patil/AppData/Local/Microsoft/WinGet/Packages/yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-N-123778-g3b55818764-win64-gpl/bin/ffmpeg.exe";
    private final ConcurrentMap<String, Path> completedDownloads = new ConcurrentHashMap<>();

    // ✅ [CHANGE 1] Helper to detect cookie-related failure from yt-dlp output lines
    private boolean requiresCookies(String output) {
        String lowerOutput = output.toLowerCase();
        return lowerOutput.contains("sign in")
                || lowerOutput.contains("login")
                || lowerOutput.contains("cookies")
                || lowerOutput.contains("this video is private")
                || lowerOutput.contains("age-restricted")
                || lowerOutput.contains("members only")
                || lowerOutput.contains("http error 403")
                || lowerOutput.contains("confirm your age")
                || lowerOutput.contains("private video");
    }

    // ✅ Endpoint 1 - Get video info (delegates to service which handles cookie fallback)
    @GetMapping("/info")
    public ResponseEntity<?> getInfo(@RequestParam String url) {
        try {
            Map<String, Object> info = service.getVideoInfo(url);
            int seconds = ((Number) info.getOrDefault("duration", 0)).intValue();

            int minutes = seconds / 60;
            int remainingSeconds = seconds % 60;

            String duration = minutes + " Min " + remainingSeconds + " Sec";

            return ResponseEntity.ok(Map.of(
                    "title", info.get("title"),
                    "duration", duration,
                    "uploader", info.getOrDefault("uploader", "N/A"),
                    "format", info.getOrDefault("ext", "mp4"),
                    "filesize", info.getOrDefault("filesize", "unknown")
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ Endpoint 2 - Download video (delegates to service which handles cookie fallback)
    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam String url,
            @RequestParam(defaultValue = "bestvideo+bestaudio/best") String format) {
        try {
            System.out.println("Download request received for: " + url);

            String filePath = service.downloadVideo(url, format);
            System.out.println("Downloaded file path: " + filePath);

            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                throw new RuntimeException("File does not exist: " + filePath);
            }

            Resource resource = new FileSystemResource(path);
            String fileName = path.getFileName().toString();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildAttachmentHeader(fileName))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(path))
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    // ✅ Endpoint 3 - SSE streaming with live progress + cookie fallback
    @GetMapping("/download/file")
    public ResponseEntity<Resource> downloadCompletedFile(@RequestParam String name) {
        try
        {
            Path path = completedDownloads.get(name);

            if (path == null) {
                path = resolveDownloadPath(name);
            }

            if (path == null || !Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(path);
            String fileName = path.getFileName().toString();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildAttachmentHeader(fileName))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(path))
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping(value = "/download/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter downloadWithProgress(@RequestParam String url) {
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("Stream download started for: " + url);
                Files.createDirectories(Paths.get(DOWNLOAD_DIR));

                long downloadStartedAt = System.currentTimeMillis();
                String outputTemplate = DOWNLOAD_DIR + "%(title)s.%(ext)s";

                // ✅ [CHANGE 2] Build base command WITHOUT cookies first
                List<String> command = new ArrayList<>(Arrays.asList(
                        YT_DLP_PATH,
                        "--format", "bestvideo+bestaudio/best",
                        "--merge-output-format", "mp4",
                        "--ffmpeg-location", FFMPEG_PATH,
                        "--force-overwrites",
                        "--newline", "--progress",
                        "-o", outputTemplate,
                        url
                ));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                // ✅ [CHANGE 3] Collect all output lines AND stream them to SSE client
                StringBuilder fullOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("Progress: " + line);
                        fullOutput.append(line).append("\n");
                        emitter.send(SseEmitter.event().data(line));
                    }
                }

                int exitCode = process.waitFor();

                // ✅ [CHANGE 4] If first attempt failed due to cookies, retry with Chrome cookies
                if (exitCode != 0 && requiresCookies(fullOutput.toString())) {
                    System.out.println("File downloading failed due to absence of cookies. Retrying with Chrome cookies...");
                    emitter.send(SseEmitter.event().data("[INFO] Authentication required. Retrying with Chrome cookies..."));

                    // ✅ [CHANGE 5] New command WITH --cookies-from-browser chrome
                    List<String> commandWithCookies = new ArrayList<>(Arrays.asList(
                            YT_DLP_PATH,
                            "--format", "bestvideo+bestaudio/best",
                            "--merge-output-format", "mp4",
                            "--ffmpeg-location", FFMPEG_PATH,
                            "--cookies-from-browser", "chrome",   // ← Pulls cookies from Chrome at runtime
                            "--force-overwrites",
                            "--newline", "--progress",
                            "-o", outputTemplate,
                            url
                    ));

                    ProcessBuilder pb2 = new ProcessBuilder(commandWithCookies);
                    pb2.redirectErrorStream(true);
                    Process process2 = pb2.start();

                    try (BufferedReader reader2 = new BufferedReader(
                            new InputStreamReader(process2.getInputStream()))) {
                        String line;
                        while ((line = reader2.readLine()) != null) {
                            System.out.println("Progress (with cookies): " + line);
                            emitter.send(SseEmitter.event().data(line));
                        }
                    }

                    int exitCode2 = process2.waitFor();

                    if (exitCode2 != 0) {
                        emitter.send(SseEmitter.event().data("[ERROR] Download failed even with Chrome cookies."));
                        emitter.completeWithError(new RuntimeException("Download failed even with Chrome cookies"));
                        return;
                    }

                } else if (exitCode != 0) {
                    // ✅ [CHANGE 6] Non-cookie failure — report error immediately, don't retry
                    emitter.send(SseEmitter.event().data("[ERROR] Download failed: " + fullOutput));
                    emitter.completeWithError(new RuntimeException("Download failed: " + fullOutput));
                    return;
                }

                Path downloadedPath = findDownloadedFile(downloadStartedAt);
                String fileName = downloadedPath.getFileName().toString();
                completedDownloads.put(fileName, downloadedPath);
                emitter.send(SseEmitter.event().name("done").data(fileName));
                emitter.complete();
                System.out.println("Stream download completed: " + downloadedPath);

            } catch (Exception e) {
                e.printStackTrace();
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private Path findDownloadedFile(long downloadStartedAt) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.list(Paths.get(DOWNLOAD_DIR))) {
            return paths
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp4"))
                    .filter(p -> lastModifiedMillis(p) >= downloadStartedAt - 2000)
                    .max((left, right) -> Long.compare(lastModifiedMillis(left), lastModifiedMillis(right)))
                    .orElseThrow(() -> new RuntimeException("Downloaded file not found"));
        }
    }

    private Path resolveDownloadPath(String fileName) {
        Path downloadDir = Paths.get(DOWNLOAD_DIR).toAbsolutePath().normalize();
        Path path = downloadDir.resolve(fileName).normalize();

        if (!path.startsWith(downloadDir)) {
            return null;
        }

        return path;
    }

    private String buildAttachmentHeader(String fileName) {
        return ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }
}
