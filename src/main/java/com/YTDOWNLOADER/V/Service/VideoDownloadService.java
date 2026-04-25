package com.YTDOWNLOADER.V.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    // ✅ [CHANGE 1] Helper method to check if output indicates a cookies-related failure
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

    public String downloadVideo(String url, String format) throws IOException, InterruptedException {
        Files.createDirectories(Paths.get(DOWNLOAD_DIR));
        String selectedFormat;
        if (format == null || format.trim().length() == 0) {
            selectedFormat = "bestvideo+bestaudio/best";
        } else {
            selectedFormat = format;
        }
        long downloadStartedAt = System.currentTimeMillis();
        String outputTemplate = DOWNLOAD_DIR + "%(title)s.%(ext)s";

        // ✅ Step 1 - Snapshot existing files BEFORE download
        Set<Path> existingFiles;
        try (java.util.stream.Stream<Path> paths = Files.list(Paths.get(DOWNLOAD_DIR))) {
            existingFiles = paths.collect(Collectors.toSet());
        }

        System.out.println("Existing files before download: " + existingFiles.size());

        // ✅ Step 2 - Build base command WITHOUT cookies first
        List<String> command = new ArrayList<>(Arrays.asList(
                YT_DLP_PATH,
                "--format", selectedFormat,
                "--merge-output-format", "mp4",
                "--ffmpeg-location", FFMPEG_PATH,
                "--force-overwrites",
                "--output", outputTemplate,
                url
        ));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        System.out.println("yt-dlp output (attempt 1 - no cookies): " + output);

        // ✅ [CHANGE 2] If download failed AND the reason seems cookie-related, retry with cookies from Chrome
        if (exitCode != 0 && requiresCookies(output)) {
            System.out.println("Download failed due to authentication/cookies. Retrying with Chrome cookies...");

            // ✅ [CHANGE 3] Build new command WITH --cookies-from-browser chrome
            List<String> commandWithCookies = new ArrayList<>(Arrays.asList(
                    YT_DLP_PATH,
                    "--format", selectedFormat,
                    "--merge-output-format", "mp4",
                    "--ffmpeg-location", FFMPEG_PATH,
                    "--cookies-from-browser", "chrome",       // ← Pulls cookies from Chrome at runtime
                    "--force-overwrites",
                    "--output", outputTemplate,
                    url
            ));

            ProcessBuilder pb2 = new ProcessBuilder(commandWithCookies);
            pb2.redirectErrorStream(true);
            Process process2 = pb2.start();

            String output2 = new String(process2.getInputStream().readAllBytes());
            int exitCode2 = process2.waitFor();

            System.out.println("yt-dlp output (attempt 2 - with Chrome cookies): " + output2);

            if (exitCode2 != 0) {
                throw new RuntimeException("Download failed even with Chrome cookies: " + output2);
            }

        } else if (exitCode != 0) {
            // ✅ [CHANGE 4] Non-cookie failure — throw immediately, no point retrying with cookies
            throw new RuntimeException("Download failed: " + output);
        } else {
            System.out.println("Download succeeded without cookies.");
        }

        // ✅ Step 3 - Find the NEW file by comparing before and after
        Set<Path> newFiles;
        try (java.util.stream.Stream<Path> paths = Files.list(Paths.get(DOWNLOAD_DIR))) {
            newFiles = paths.collect(Collectors.toSet());
        }

        newFiles.removeAll(existingFiles);

        System.out.println("Newly downloaded files: " + newFiles);

        // ✅ Step 4 - Return the newly downloaded file
        Path downloadedPath = newFiles.stream()
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp4"))
                .findFirst()
                .orElseGet(() -> findDownloadedFile(downloadStartedAt));

        return downloadedPath.toString();
    }

    private Path findDownloadedFile(long downloadStartedAt) {
        try (java.util.stream.Stream<Path> paths = Files.list(Paths.get(DOWNLOAD_DIR))) {
            return paths
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp4"))
                    .filter(p -> lastModifiedMillis(p) >= downloadStartedAt - 2000)
                    .max((left, right) -> Long.compare(lastModifiedMillis(left), lastModifiedMillis(right)))
                    .orElseThrow(() -> new RuntimeException("Downloaded file not found"));
        } catch (IOException e) {
            throw new RuntimeException("Unable to find downloaded file", e);
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public Map<String, Object> getVideoInfo(String url) throws IOException, InterruptedException {

        // ✅ [CHANGE 5] Try fetching video info WITHOUT cookies first
        List<String> command = new ArrayList<>(Arrays.asList(
                YT_DLP_PATH,
                "--dump-json", "--no-download", url
        ));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String json = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        System.out.println("Video info fetch attempt 1 (no cookies) exit code: " + exitCode);

        // ✅ [CHANGE 6] If info fetch failed due to cookies, retry with Chrome cookies
        if (exitCode != 0 && requiresCookies(json)) {
            System.out.println("Video info fetch failed due to authentication. Retrying with Chrome cookies...");

            List<String> commandWithCookies = new ArrayList<>(Arrays.asList(
                    YT_DLP_PATH,
                    "--dump-json", "--no-download",
                    "--cookies-from-browser", "chrome",       // ← Pulls cookies from Chrome at runtime
                    url
            ));

            ProcessBuilder pb2 = new ProcessBuilder(commandWithCookies);
            pb2.redirectErrorStream(true);
            Process process2 = pb2.start();

            json = new String(process2.getInputStream().readAllBytes());
            int exitCode2 = process2.waitFor();

            System.out.println("Video info fetch attempt 2 (with Chrome cookies) exit code: " + exitCode2);

            if (exitCode2 != 0) {
                throw new RuntimeException("Failed to fetch video info even with Chrome cookies: " + json);
            }

        } else if (exitCode != 0) {
            throw new RuntimeException("Failed to fetch video info: " + json);
        }

        System.out.println("Video info fetched successfully");

        return new ObjectMapper().readValue(json, Map.class);
    }
}
