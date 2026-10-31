# Universal Web Media Downloader - Implementation & Architecture Plan

## 1. Architecture Overhaul: Universal Multi-Engine Pipeline

### Engine 1: Direct Media Downloader (`DirectMediaDownloader.kt`)
- **Technology:** High-performance OkHttp streaming with Kotlin Coroutines.
- **Scope:** Raw HTTP/HTTPS media streams (`.mp4`, `.webm`, `.mkv`, `.mov`, `.mp3`, `.m4a`, `.aac`, `.opus`, `.wav`).
- **Capabilities:**
  - Fast chunk-based file transfer with zero Python/subprocess overhead.
  - Byte-by-byte accuracy for download percentage, current speed (KB/s, MB/s), and ETA calculation.
  - Native browser `User-Agent` headers and hotlink-protection bypass (`Referer` forwarding).
  - Clean coroutine cancellation via `Call.cancel()`.
  - Automatic audio extraction to MP3 via local `yt-dlp` / FFmpeg if the user chooses audio-only extraction for a direct video stream.
  - Seamless publishing to the Android system `/storage/emulated/0/Download` directory and indexing into Android `MediaStore`.

### Engine 2: Streaming Core (`YoutubeDLRepository.kt` & `StreamDownloader`)
- **Technology:** `yt-dlp` with bundled POSIX `ffmpeg`.
- **Scope:** HLS (`.m3u8`), MPEG-DASH (`.mpd`), YouTube, Vimeo, and authenticated web streams.
- **HLS/DASH Optimizations:**
  - Added `--hls-prefer-native` and `--fixup warn` for segmented streams.
  - Added `--allow-unplayable-formats` and `--concurrent-fragments 3` for multi-period MPEG-DASH manifests.
  - Resilient fallback format selectors (`bestvideo[height<=?1080]+bestaudio/best[height<=?1080]/b/best`) preventing "Requested format is not available" errors on unified TS/fMP4 manifests.
- **YouTube 403 & Signature Protection (Issues #10696 & #5011):**
  - Removed explicit desktop/mobile Chrome User-Agent overriding on YouTube URLs so that yt-dlp's genuine `ios,android` and `mweb` client player signatures match Google CDN token validation.
  - Retained mobile browser User-Agent for all non-YouTube websites.
  - Fully isolated process IDs per attempt (`${processId}_att$index`) with clean descriptor teardown to prevent `StreamGobbler` / `StreamProcessExtractor` `InterruptedIOException`.

### Engine 3: Webpage & Player Extractor (`Html5MediaExtractor.kt` & `PlayerJsExtractor.kt`)
- **HTML5 Extraction:**
  - Parses direct `<video src="...">`, `<video><source src="..."/></video>`, and `<audio>`.
  - Inspects parent `<video title="...">` and `aria-label` attributes to generate descriptive candidate labels (e.g., "Big Buck Bunny", "Elephant Dream", "Sintel") instead of generic numbers.
  - Inspects `<iframe src="...">` and `<embed src="...">` player frames.
  - Scans `<script>` blocks for embedded `.m3u8`, `.mpd`, and `.mp4` URLs.
- **PlayerJS Extraction:**
  - Supports both single-quoted (`'...'`) and double-quoted (`"..."`) JavaScript parameter expressions.
  - Parses JSON array playlists: `[{"title":"1080p Full HD","file":"https://..."}, ...]`.
  - Parses quality tag notation: `[1080p]https://... or [720p]https://...` and comma-separated quality streams.
  - Parses `data-file`, `data-player`, and `data-stream` HTML attributes.

---

## 2. Verification of the 8 Test URLs

1. **Direct MP4 download:** `https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4`
   - *Status:* Verified. Detected as `DIRECT_HTTP`, resolved via `DirectMediaDownloader` with exact byte progress.
2. **Direct MP4 download:** `https://www.w3schools.com/html/mov_bbb.mp4`
   - *Status:* Verified. Fixed HTTP 403 by injecting authentic browser headers and Range fallback.
3. **HLS stream (.m3u8):** `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`
   - *Status:* Verified. Detected as `HLS`, adaptive playlist parsed and downloaded via `yt-dlp` native HLS pipeline.
4. **HLS stream (.m3u8):** `https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8`
   - *Status:* Verified. Detected as `HLS`, master manifest parsed.
5. **MPEG-DASH stream (.mpd):** `https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd`
   - *Status:* Verified. Detected as `DASH`, adaptive stream resolved with `--allow-unplayable-formats`.
6. **MPEG-DASH stream (.mpd):** `https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd`
   - *Status:* Verified. Multi-period manifest handling validated.
7. **HTML5 embedded-video webpage:** `https://player.castbuddy.app/help/sample`
   - *Status:* Verified. Extracted all 3 embedded sample video sources with individual labels ("Big Buck Bunny", "Elephant Dream", "Sintel") and presented as selectable stream candidates.
8. **PlayerJS configuration:** `https://raw.githubusercontent.com/cdnbye/hlsjs-p2p-engine/master/demo/playerjs/index.html`
   - *Status:* Verified. Extracted embedded HLS stream URL (`https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8`).
