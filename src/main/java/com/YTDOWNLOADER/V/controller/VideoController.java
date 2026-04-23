package com.YTDOWNLOADER.V.controller;

import com.YTDOWNLOADER.V.Service.VideoDownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    @Autowired
    private VideoDownloadService service;

    private static final String DOWNLOAD_DIR = "C:/downloads/";
    private static final String YT_DLP_PATH = "C:/Users/patil/AppData/Local/Microsoft/WinGet/Packages/yt-dlp.yt-dlp_Microsoft.Winget.Source_8wekyb3d8bbwe/yt-dlp.exe";
    private static final String FFMPEG_PATH = "C:/Users/patil/AppData/Local/Microsoft/WinGet/Packages/yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-N-123778-g3b55818764-win64-gpl/bin/ffmpeg.exe";

    // ✅ Endpoint 1 - Get video info
    @GetMapping("/info")
    public ResponseEntity<?> getInfo(@RequestParam String url) {
        try {
            Map<String, Object> info = service.getVideoInfo(url);
            double seconds = ((Number) info.getOrDefault("duration", 0)).intValue();
            String formattedDuration = (seconds / 60) + " Min";
            return ResponseEntity.ok(Map.of(
                    "title",     info.get("title"),
                    "duration",  formattedDuration
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ Endpoint 2 - Download video (returns file)
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

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + path.getFileName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(path))
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    // ✅ Endpoint 3 - SSE streaming with live progress
    @GetMapping(value = "/download/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter downloadWithProgress(@RequestParam String url) {
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("Stream download started for: " + url);

                ProcessBuilder pb = new ProcessBuilder(
                        YT_DLP_PATH,
                        "--format", "bestvideo+bestaudio/best",
                        "--merge-output-format", "mp4",
                        "--ffmpeg-location", FFMPEG_PATH,
                        "--newline", "--progress",
                        "-o", DOWNLOAD_DIR + "%(title)s.%(ext)s",
                        url
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("Progress: " + line);
                        emitter.send(SseEmitter.event().data(line));
                    }
                }

                process.waitFor();
                emitter.complete();
                System.out.println("Stream download completed");

            } catch (Exception e) {
                e.printStackTrace();
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}