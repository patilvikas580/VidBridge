package com.YTDOWNLOADER.V.controller;

import com.YTDOWNLOADER.V.Service.VideoDownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.download.dir}")
    private String DOWNLOAD_DIR;

    @Value("${app.yt-dlp.path}")
    private String YT_DLP_PATH;

    @Value("${app.ffmpeg.path}")
    private String FFMPEG_PATH;


    private final ConcurrentMap<String, Path> completedDownloads = new ConcurrentHashMap<>();


    // Get video info (delegates to service which handles cookie fallback)
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

    // Download video (delegates to service which handles cookie fallback)
    @GetMapping("/download")
    public ResponseEntity<Resource> download( @RequestParam String url, @RequestParam(defaultValue = "bestvideo+bestaudio/best") String format) {
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

    //Return HTTP response for "/download/stream" called through JS
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

    //SSE streaming with live progress + cookie fallback
    @GetMapping(value = "/download/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter downloadWithProgress(@RequestParam String url) {
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("Stream download started for: " + url);
                Files.createDirectories(Paths.get(DOWNLOAD_DIR));

                long downloadStartedAt = System.currentTimeMillis();
                String outputTemplate = DOWNLOAD_DIR + "%(title)s.%(ext)s";

                // command Without cookies first
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

                // Collect all output lines AND stream them to SSE client
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

                // If first attempt failed due to cookies, retry with Chrome cookies
                if (exitCode != 0 && service.requiresCookies(fullOutput.toString()))
                {
                    System.out.println("File downloading failed due to absence of cookies. Retrying with Chrome cookies...");
                    emitter.send(SseEmitter.event().data("[INFO] Authentication required. Retrying with Chrome cookies..."));

                    //  command with cookies from browser chrome
                    List<String> commandWithCookies = new ArrayList<>(Arrays.asList(
                            YT_DLP_PATH,
                            "--format", "bestvideo+bestaudio/best",
                            "--merge-output-format", "mp4",
                            "--ffmpeg-location", FFMPEG_PATH,
                            "--cookies-from-browser", "chrome",
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
                    //  Non-cookie failure
                    emitter.send(SseEmitter.event().data("[ERROR] Download failed: " + fullOutput));
                    emitter.completeWithError(new RuntimeException("Download failed: " + fullOutput));
                    return;
                }

                Path downloadedPath = service.findDownloadedFile(downloadStartedAt);
                String fileName = downloadedPath.getFileName().toString();
                completedDownloads.put(fileName, downloadedPath);
                emitter.send(SseEmitter.event().name("done").data(fileName));
                emitter.complete();
                System.out.println("File download completed: " + downloadedPath);

            } catch (Exception e) {
                e.printStackTrace();
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    //Security layer
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

}
