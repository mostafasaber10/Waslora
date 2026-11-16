# Waslora / وصلورا (0.4.1)

Waslora is a clean Android implementation of the useful workflows identified in the two supplied APKs. The chosen name is a working product name; trademark and store-name availability have not been cleared. The vector logo is in `brand/logo.svg` and the Android icon is in `app/src/main/res/drawable/ic_waslora.xml`.

## What is implemented

- Direct HTTP/HTTPS media-file downloads. When the server advertises byte-range support, files of at least 16 MB are fetched over up to six parallel connections; interrupted sequential downloads can resume.
- Public platform-link downloads through the embedded yt-dlp 2026.10.26 and FFmpeg engine. YouTube, Facebook, Instagram, TikTok, X/Twitter, and other supported yt-dlp domains are processed on the phone; no Resolver or laptop service is required. Platform extractors can change or be restricted by the source service, so individual links may not always work.
- User-selectable maximum video quality from 360p through 4K, or the best available quality. If a source does not offer the selected maximum, Waslora chooses the highest lower quality; audio is merged automatically where needed. Direct media-file URLs are downloaded as served by their source.
- Downloads can be paused and resumed from the download screen. Platform downloads retain yt-dlp partial data and direct downloads continue from their temporary file while the app remains active.
- Video audio extraction to M4A or MP3. M4A copies a compatible AAC track without re-encoding and automatically decodes/re-encodes other supported audio codecs to AAC. MP3 decodes supported audio through Android MediaCodec and encodes it in a worker using the bundled `lamejs` library.
- Two-way phone/laptop transfer over the same Wi-Fi network, using the active local-network address, a temporary tokenized URL, and a browser page served by the phone. The transfer service holds CPU/Wi-Fi locks only while the user runs the session; browser uploads show progress and accept both fixed-length and chunked HTTP bodies.
- Completed downloads, conversions, and laptop uploads are copied to the public `Download/Waslora` folder on Android 10+, so they appear in the Files app. The app also offers open/share actions and can stage selected phone files for laptop download.
- Android Share support for incoming links and video files.

## Platform links

A page link from YouTube, Facebook, Instagram, TikTok, or another supported platform is processed by the embedded yt-dlp engine. The engine does not use cookies, passwords, browser sessions, DRM keys, or private-content workarounds; private, login-only, signed, DRM-protected, or unavailable videos can still fail.

## Build

Requirements: Windows PowerShell, Android SDK Platform 35, Android Build Tools 36.0.0, and a complete JDK 17 or newer. Set `JAVA_HOME` to the JDK installation folder, then run from this directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

The signed debug APK is written to `out\Waslora-debug.apk`. The stable local debug key is kept in `.build-cache/debug.keystore`, so repeated test builds retain the same signing identity. It is for testing only, not store release. The supplied APKs contain compiled code, not either app's original project, so this is a fresh implementation of their workflows rather than a source-level merge.

## iPhone version

An iOS SwiftUI project is included under `ios/Waslora.xcodeproj`. It provides direct-file downloads with background `URLSession`, a media library, M4A conversion through AVFoundation, and two-way Wi-Fi transfer. Open it on a Mac with Xcode 15 or newer, add an Apple Developer signing team, and test on a real iPhone. The iOS build intentionally accepts direct file URLs; App Store distribution of third-party platform-page downloading requires authorization from the relevant content platforms. MP3 encoding still needs a separately licensed encoder integrated into the Xcode target.

This workspace includes a portable JDK 21 cache under `.build-cache`, which the build script detects automatically. The Java sources compile, DEX is generated, and APK signing and alignment are checked by the build script. The APK bundles the yt-dlp/Python and FFmpeg native engine, so its size is substantially larger than a UI-only build.

## Checks

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\run-tests.ps1
```

The regression suite covers PCM 8/16-bit and float conversion, stereo downmixing, direct-link and platform-page handling, fixed-length and chunked file uploads, app-to-native bridge wiring, laptop-page syntax, and MP3 worker output.

## GitHub and releases

This repository uses Git LFS because the embedded Android AAR files are larger than GitHub's normal single-file limit. A Windows GitHub Actions workflow runs tests, builds a test APK, and can prepare a signed draft release when a version tag is pushed.

To enable signed GitHub releases:

1. Create a private signing key and keep a secure backup. Never commit it:

   ```powershell
   keytool -genkeypair -v -keystore waslora-release.jks -alias waslora -keyalg RSA -keysize 2048 -validity 10000
   ```

2. In the GitHub repository, open **Settings → Secrets and variables → Actions** and add `WASLORA_RELEASE_KEYSTORE_BASE64`, `WASLORA_RELEASE_ALIAS`, `WASLORA_RELEASE_STORE_PASSWORD`, and `WASLORA_RELEASE_KEY_PASSWORD`. For the Base64 secret, encode the `.jks` file locally without committing or posting it.
3. Push a version tag such as `v0.4.1`. Actions runs tests and creates a **draft** release with the signed APK attached. Review it on GitHub and publish when ready.

For a local test build, use `build.ps1`. For a local signed release, set the `WASLORA_RELEASE_*` environment variables and run `build.ps1 -Release -VersionName 0.4.1 -VersionCode 6`.

If you are setting up the remote for the first time, create an empty GitHub repository, then run:

```powershell
git remote add origin https://github.com/USERNAME/Waslora.git
git push -u origin main
```

Install Git LFS before cloning or pushing. APKs and signing keys are intentionally ignored from source control. GitHub is the place for source code, issues, and release notes. Android users can download the signed release APK from Releases. iPhone users need TestFlight or the App Store; a GitHub repository alone cannot install an iOS app on their phones.

## Third-party notice

The embedded platform downloader (`youtubedl-android`, yt-dlp, and FFmpeg) is GPL-3.0; the full license and notices are included in `app/src/main/assets/licenses`. Anyone redistributing this build should review and follow GPL-3.0 obligations. The MP3 encoder is `lamejs` 1.2.1, bundled as the separate asset `app/src/main/assets/lame.min.js`. Its package metadata declares LGPL-3.0; the packaged project notice is in `vendor/LAME-LICENSE.txt`, and the source package is retained under `vendor/unpacked/package`. See the [upstream project](https://github.com/zhuker/lamejs) and [GNU LGPL-3.0 text](https://www.gnu.org/licenses/lgpl-3.0.en.html).
