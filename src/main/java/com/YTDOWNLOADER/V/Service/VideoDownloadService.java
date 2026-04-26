package com.YTDOWNLOADER.V.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.download.dir}")
    private String DOWNLOAD_DIR;

    @Value("${app.yt-dlp.path}")
    private String YT_DLP_PATH;

    @Value("${app.ffmpeg.path}")
    private String FFMPEG_PATH;

    //  Method to check if output indicates a cookies-related failure
    public boolean requiresCookies(String output) {
        String urlInfo = output.toLowerCase();
        return urlInfo.contains("sign in")
                || urlInfo.contains("login")
                || urlInfo.contains("cookies")
                || urlInfo.contains("this video is private")
                || urlInfo.contains("age-restricted")
                || urlInfo.contains("members only")
                || urlInfo.contains("http error 403")
                || urlInfo.contains("confirm your age")
                || urlInfo.contains("private video");
    }

    //Download method for PostMan testing and Direct download from phone
    public String downloadVideo(String url, String format) throws IOException, InterruptedException {
        Files.createDirectories(Paths.get(DOWNLOAD_DIR));
        String videoQuality;
        if (format == null || format.trim().length() == 0) {
            videoQuality = "bestvideo+bestaudio/best";
        } else {
            videoQuality = format;
        }

        long downloadStartedAt = System.currentTimeMillis();
        String outputTemplate = DOWNLOAD_DIR + "%(title)s.%(ext)s";

        // Store existing files in Map Before download
        Set<Path> existingFiles;
        try (java.util.stream.Stream<Path> paths = Files.list(Paths.get(DOWNLOAD_DIR))) {
            existingFiles = paths.collect(Collectors.toSet());
        }
//        System.out.println("Existing files before download: " + existingFiles.size());

        // Try downloading video without cookies
        List<String> command = new ArrayList<>(Arrays.asList(
                YT_DLP_PATH,
                "--format", videoQuality,
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


        // If download failed and the reason seems cookie-related, retry with cookies from Chrome
        if (exitCode != 0 && requiresCookies(output))
        {
            System.out.println("Download failed due to authentication/cookies. Retrying with Chrome cookies...");

            // command with cookies
            List<String> commandWithCookies = new ArrayList<>(Arrays.asList(
                    YT_DLP_PATH,
                    "--format", videoQuality,
                    "--merge-output-format", "mp4",
                    "--ffmpeg-location", FFMPEG_PATH,
                    "--cookies-from-browser", "chrome",
                    "--force-overwrites",
                    "--output", outputTemplate,
                    url
            ));

            ProcessBuilder pb2 = new ProcessBuilder(commandWithCookies);
            pb2.redirectErrorStream(true);
            Process process2 = pb2.start();

            String output2 = new String(process2.getInputStream().readAllBytes());
            int exitCode2 = process2.waitFor();



            if (exitCode2 != 0) {
                throw new RuntimeException("Download failed even with Chrome cookies: " + output2);
            }

        }
        else if (exitCode != 0) {
            //even if cookies failed
            throw new RuntimeException("Download failed : " + output);
        } else {
            System.out.println("Download succeeded without cookies.");
        }

        // Find the new file by comparing map before and after
        Set<Path> newFiles;
        try (java.util.stream.Stream<Path> paths = Files.list(Paths.get(DOWNLOAD_DIR))) {
            newFiles = paths.collect(Collectors.toSet());
        }

        newFiles.removeAll(existingFiles);

        Path downloadedPath = null;

        for (Path p : newFiles) {
            String fileName = p.getFileName().toString().toLowerCase();

            // Step 2: check if it's a mp4 file
            if (fileName.endsWith(".mp4")) {
                downloadedPath = p;
                break;
            }
        }
    //  if not found, fallback
        if (downloadedPath == null) {
            downloadedPath = findDownloadedFile(downloadStartedAt);
        }
        return downloadedPath.toString();
    }

    //Provide Download path to SSE Emmiter(/Strem endpoint) and in case of failure to find  Provides download path to downloadVideo function
    public Path findDownloadedFile(long downloadStartedAt) {
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

    //helper function
    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    //Function to retrieve video info mapped - with controller
    public Map<String, Object> getVideoInfo(String url) throws IOException, InterruptedException {

        //Try fetching video info without cookies first
        List<String> command = new ArrayList<>(Arrays.asList(YT_DLP_PATH, "--dump-json", "--no-download", url));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String json = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

//        System.out.println("Video info fetch attempt 1 (no cookies) : " + exitCode);

        // If info fetch failed due to cookies, retry with Chrome cookies
        if (exitCode != 0 && requiresCookies(json)) {
            System.out.println("Video info fetch failed due to authentication. Retrying with Chrome cookies...");

        //Attached cookies in command
            List<String> commandWithCookies = new ArrayList<>(Arrays.asList(YT_DLP_PATH, "--dump-json", "--no-download", "--cookies-from-browser", "chrome", url));

            ProcessBuilder pb2 = new ProcessBuilder(commandWithCookies);
            pb2.redirectErrorStream(true);
            Process process2 = pb2.start();

            json = new String(process2.getInputStream().readAllBytes());
            int exitCode2 = process2.waitFor();

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
