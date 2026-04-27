# 🎬 VidBridge

> A Spring Boot-powered REST API backend for downloading videos using `yt-dlp` — built entirely for **learning purposes**.

<br>

---

## ⚠️ IMPORTANT DISCLAIMER

> **This project is developed SOLELY for learning and educational purposes.**
>
> - ❌ Do **NOT** use this tool to download **private, copyrighted, or restricted** videos
> - ❌ Do **NOT** use this to violate the **Terms of Service** of any platform (YouTube, Instagram, etc.)
> - ❌ Do **NOT** use this for **commercial use, redistribution**, or any **unethical purpose**
> - ✅ This project is meant to **explore Spring Boot REST API design**, process management in Java, and backend architecture
>
> The developer holds **no responsibility** for any misuse of this software. Use it wisely and ethically. Respect content creators and platform policies.

---

## 🌐 Deployment Status

> 🔴 **VidBridge is NOT deployed on any server.**
>
> This is a **run-it-locally** project. To use it, clone the repository, set it up on your local machine, and run it. All setup instructions are provided below.

---

## 📸 Screenshots

> _Home Page_
<img width="1887" height="858" alt="Home P" src="https://github.com/user-attachments/assets/cf229eb5-6006-49fa-90d4-89263c433414" />
 
> _Download_
<img width="1001" height="772" alt="DDownload" src="https://github.com/user-attachments/assets/7be5515c-23c4-41c0-8c17-0aee51b7b109" />


> _PostMan testing_
> ```
 <img width="1110" height="813" alt="Screenshot 2026-04-24 220133" src="https://github.com/user-attachments/assets/baf1ed06-7d7b-432a-a9b7-5256d4051c52" />
  <img width="1110" height="907" alt="Screenshot 2026-04-24 220334" src="https://github.com/user-attachments/assets/73f47eb5-5681-4dd3-a561-8322637fcf6a" />

> ```
<img width="846" height="435" alt="Screenshot 2026-04-24 215824" src="https://github.com/user-attachments/assets/7b552fd4-3815-49e5-a9ee-80fe53bf6d4a" />
<img width="1893" height="848" alt="Screenshot 2026-04-24 220447" src="https://github.com/user-attachments/assets/acaf0eb4-077a-48df-a4ac-926c353403a8" />
<img width="1607" height="866" alt="Screenshot 2026-04-24 220259" src="https://github.com/user-attachments/assets/8d2e6dd1-2958-44c5-b89f-d34ce31c82a6" />
<img width="1571" height="917" alt="Screenshot 2026-04-24 215939" src="https://github.com/user-attachments/assets/bd690ebc-7f0c-44cf-adb7-8826118f7be4" />

---

## 📖 About VidBridge

VidBridge is a backend-focused project built with **Spring Boot 3** and **Java 17**. It integrates `yt-dlp` as a backend subprocess to handle video downloading, exposes the functionality through clean REST endpoints, and serves the downloaded file back to the client — including support for generating shareable download links accessible from **other devices on the same network** (like your phone).

The frontend was kept intentionally minimal (AI-generated HTML) since the primary goal was to build and test a **solid, well-structured backend**. The frontend does not fully utilize all backend capabilities — it is open to contributions.

---

## ✨ Features

- 🔗 Accepts any `yt-dlp`-supported video URL
- 🍪 Uses **browser cookies** passed to `yt-dlp` to handle age-restricted or auth-required content
- 🧹 **File name sanitization** — strips special characters, emojis, spaces for cross-platform compatibility
- 📦 **File serving** via Spring's `FileSystemResource` through clean REST endpoints
- 📱 **Download on your phone** — generate a download link from the UI and access it via your machine's local IP from any device on the same network
- 🧪 All endpoints tested with **Postman**
- ⚡ Lightweight — no database, no auth layer, pure REST + process I/O

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| Build Tool | Maven |
| Video Engine | yt-dlp (subprocess) |
| Boilerplate Reduction | Lombok |
| API Testing | Postman |
| Frontend | HTML (AI-generated, minimal) |

---

## 🏗️ Project Structure

```
VidBridge/
├── src/
│   └── main/
│       ├── java/com/YTDOWNLOADER/V/
│       │   ├── controller/        # REST Controllers
│       │   ├── service/           # Business logic & yt-dlp process handling
│       │   └── model/             # Request/Response models (Lombok)
│       └── resources/
│           ├── static/            # Frontend HTML (AI-generated)
│           └── application.properties
├── pom.xml
└── README.md
```

---

## 🔌 API Endpoints

The backend exposes **3 REST endpoints**. All requests and responses are in JSON unless otherwise noted.

Base URL (local): `http://localhost:8080`

---

### 1. 📋 `POST /api/video/info` — Fetch Video Metadata

Fetches video title, duration, thumbnail, and format details **without downloading** the video. Uses `yt-dlp --dump-json` under the hood.

**Request:**
```http
POST /api/video/info
Content-Type: application/json
```

```json
{
  "url": "https://www.youtube.com/watch?v=example"
}
```

**Response:**
```json
{
  "title": "Example Video Title",
  "duration": 245,
  "thumbnail": "https://i.ytimg.com/vi/example/maxresdefault.jpg",
  "uploader": "Channel Name",
  "formats": ["mp4", "webm"]
}
```

**What happens internally:**
- Spring receives the URL via `@RequestBody`
- A `ProcessBuilder` is used to invoke `yt-dlp --dump-json <url>`
- The JSON output from yt-dlp is parsed and mapped to a response model using Lombok `@Data`
- Cookies are optionally passed via `--cookies` flag to handle restricted content

---

### 2. 🚀 `POST /api/video/download` — Download a Video

Triggers the actual video download on the server. The video is saved to a configured local directory with a sanitized filename.

**Request:**
```http
POST /api/video/download
Content-Type: application/json
```

```json
{
  "url": "https://www.youtube.com/watch?v=example",
  "format": "mp4"
}
```

**Response:**
```json
{
  "message": "Download successful",
  "fileName": "Example_Video_Title.mp4",
  "downloadUrl": "/api/video/serve/Example_Video_Title.mp4"
}
```

**What happens internally:**
- `ProcessBuilder` spins up `yt-dlp` with the given URL and format flag
- Output is captured via `process.getInputStream()` for logging/error detection
- The filename returned by yt-dlp is sanitized:
  - All special characters (`/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`) are stripped
  - Spaces replaced with underscores
  - Emojis and unicode symbols removed
- File is saved to the server's local download directory
- A `downloadUrl` is returned pointing to the `/serve` endpoint

---

### 3. 📥 `GET /api/video/serve/{fileName}` — Serve / Stream the Downloaded File

Serves the downloaded video file back to the client as a binary stream. This is what enables downloading to your phone or any other device.

**Request:**
```http
GET /api/video/serve/Example_Video_Title.mp4
```

**Response:**
- Content-Type: `video/mp4` (or appropriate MIME type)
- Body: Binary file stream via `FileSystemResource`

**What happens internally:**
- Controller reads the `{fileName}` path variable
- Constructs a `FileSystemResource` pointing to the download directory
- Returns a `ResponseEntity<Resource>` with appropriate Content-Disposition headers for download
- The browser (or mobile device) treats this as a direct file download

**📱 Download on Your Phone:**
> Once you have the `downloadUrl`, simply replace `localhost` with your **machine's local IP address** (e.g., `192.168.1.5`) and open the link from your phone's browser on the same Wi-Fi network. The file will download directly to your phone.
>
> Example: `http://192.168.1.5:8080/api/video/serve/Example_Video_Title.mp4`

---

## 🍪 How Cookies Are Used

YouTube and certain other platforms increasingly require authentication signals to serve video metadata or allow downloads, especially for:
- Age-restricted videos
- Videos requiring login
- Bypassing bot detection / CAPTCHA challenges

VidBridge handles this by passing a **cookies file** to `yt-dlp` using the `--cookies` flag when invoking the subprocess.

**How it works:**

1. Export your browser cookies to a `cookies.txt` file (Netscape format) using a browser extension like *Get cookies.txt LOCALLY*
2. Place the file in the project's configured cookies directory
3. When the backend invokes `yt-dlp`, it appends `--cookies /path/to/cookies.txt` to the command
4. `yt-dlp` uses these cookies to authenticate the request as if it were coming from your logged-in browser session

```java
// Simplified example of how yt-dlp is invoked with cookies
List<String> command = new ArrayList<>();
command.add("yt-dlp");
command.add("--cookies");
command.add(cookiesFilePath);   // Path to your exported cookies.txt
command.add("-o");
command.add(outputPath + "/%(title)s.%(ext)s");
command.add(videoUrl);

ProcessBuilder processBuilder = new ProcessBuilder(command);
processBuilder.redirectErrorStream(true);
Process process = processBuilder.start();
```

> ⚠️ **Never share your cookies file.** It contains session tokens that give access to your account. Add `cookies.txt` to `.gitignore` immediately.

---

## 🧹 File Name Sanitization

Raw video titles from platforms often contain characters that break file systems or URLs. VidBridge sanitizes them before saving:

```java
// Sanitization logic (simplified)
String sanitized = rawTitle
    .replaceAll("[^a-zA-Z0-9._\\-]", "_")  // Replace unsafe chars with underscore
    .replaceAll("_+", "_")                  // Collapse multiple underscores
    .replaceAll("^_|_$", "")               // Trim leading/trailing underscores
    .trim();
```

This ensures:
- ✅ Safe file system storage on Windows, Linux, and macOS
- ✅ URL-safe filenames for the `/serve` endpoint
- ✅ No broken download links due to special characters or spaces

---

## ⚙️ Prerequisites

Before running VidBridge, make sure you have the following installed:

| Dependency | Version | Install |
|---|---|---|
| Java JDK | 17+ | [adoptium.net](https://adoptium.net) |
| Maven | 3.8+ | [maven.apache.org](https://maven.apache.org) |
| yt-dlp | Latest | [github.com/yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp) |
| ffmpeg *(optional)* | Latest | [ffmpeg.org](https://ffmpeg.org) — needed for format merging |

---

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/patilvikas580/VidBridge.git
cd VidBridge
```

### 2. Verify yt-dlp is Installed

```bash
yt-dlp --version
```

If not installed:
```bash
# Linux / macOS
sudo curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp
sudo chmod a+rx /usr/local/bin/yt-dlp

# Windows (via pip)
pip install yt-dlp
```

### 3. Configure Application Properties

Edit `src/main/resources/application.properties`:

```properties
server.port=8080
download.directory=/path/to/your/download/folder
cookies.file.path=/path/to/cookies.txt
```

### 4. Build and Run

```bash
./mvnw spring-boot:run
```

Or build a JAR first:
```bash
./mvnw clean package
java -jar target/V-0.0.1-SNAPSHOT.jar
```

### 5. Open the UI

Navigate to `http://localhost:8080` in your browser.

---

## 🧪 Testing with Postman

You can test all endpoints directly with Postman:

1. Import the base URL: `http://localhost:8080`
2. Create a POST request to `/api/video/info` with a JSON body containing a `url`
3. Then POST to `/api/video/download` with `url` and `format`
4. Finally, use the returned `downloadUrl` in a GET request to `/api/video/serve/{fileName}`

> _See the Postman screenshot in the Screenshots section above for reference._

---

## 🎨 Frontend Note

The UI included in this project was **AI-generated** and serves purely as a visual demo. It does **not fully utilize** all backend capabilities. If you'd like to contribute by building a proper frontend that wires up all API endpoints, you're very welcome to!

**Areas where frontend contributions would help:**
- Format selection before downloading
- Download queue / progress tracking
- Error display and retry logic
- Mobile-responsive layout

---

## 🤝 Contributing

Contributions are welcome, especially on the frontend side! Here's how:

1. Fork the repository
2. Create a new branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -m 'Add your feature'`
4. Push to the branch: `git push origin feature/your-feature`
5. Open a Pull Request

---

## 📄 License

This project is open source and available under the [MIT License](LICENSE).

---

## 👨‍💻 Author

**Vikas Patil**
- GitHub: [@patilvikas580](https://github.com/patilvikas580)

---

<div align="center">

⭐ If you found this project helpful or interesting, consider giving it a star!

**Built with ❤️ for learning Spring Boot and REST API design**

</div>
