package com.YTDOWNLOADER.V.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VideoDownloadService {

    private static final String DOWNLOAD_DIR = "C:/downloads/";
    private static final String YT_DLP_PATH = "C:/Users/patil/AppData/Local/Microsoft/WinGet/Packages/yt-dlp.yt-dlp_Microsoft.Winget.Source_8wekyb3d8bbwe/yt-dlp.exe";
    private static final String FFMPEG_PATH = "C:/Users/patil/AppData/Local/Microsoft/WinGet/Packages/yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-N-123778-g3b55818764-win64-gpl/bin/ffmpeg.exe";

    public String downloadVideo(String url, String format) throws IOException, InterruptedException {
        Files.createDirectories(Paths.get(DOWNLOAD_DIR));

        // ✅ Step 1 - Snapshot existing files BEFORE download
        Set<Path> existingFiles = Files.list(Paths.get(DOWNLOAD_DIR))
                .collect(Collectors.toSet());

        System.out.println("Existing files before download: " + existingFiles.size());

        // ✅ Step 2 - Run yt-dlp to download the new video
        List<String> command = Arrays.asList(
                YT_DLP_PATH,
                "--format", "bestvideo+bestaudio/best",
                "--merge-output-format", "mp4",
                "--ffmpeg-location", FFMPEG_PATH,
                "--output", DOWNLOAD_DIR + "%(title)s.%(ext)s",
                url
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        System.out.println("yt-dlp output: " + output);

        if (exitCode != 0) {
            throw new RuntimeException("Download failed: " + output);
        }

        // ✅ Step 3 - Find the NEW file by comparing before and after
        Set<Path> newFiles = Files.list(Paths.get(DOWNLOAD_DIR))
                .collect(Collectors.toSet());

        // Find files that weren't there before
        newFiles.removeAll(existingFiles);

        System.out.println("Newly downloaded files: " + newFiles);

        // ✅ Step 4 - Return the newly downloaded file
        return newFiles.stream()
                .filter(p -> p.toString().endsWith(".mp4"))
                .findFirst()
                .map(Path::toString)
                .orElseThrow(() -> new RuntimeException("New file not found after download"));
    }

    public Map<String, Object> getVideoInfo(String url) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                YT_DLP_PATH,
                "--dump-json", "--no-download", url
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        String json = new String(process.getInputStream().readAllBytes());
        process.waitFor();

        System.out.println("Video info fetched successfully");

        return new ObjectMapper().readValue(json, Map.class);
    }
}