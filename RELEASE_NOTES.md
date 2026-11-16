# Waslora 0.4.1

## Android

- Downloads large direct files and supported public platform links on the device.
- Adds maximum video quality choices from 360p through 4K.
- Updates yt-dlp to the official 2026.10.26 release for newer platform extractors.
- Adds a black Tata Studios opening splash screen.
- Adds pause and resume controls for direct-file and platform video downloads.
- Adds a dedicated media library with filters for video, audio, and images.
- Re-encodes supported non-AAC audio tracks to AAC when creating M4A files.
- Improves local transfer for large files over the same Wi-Fi network.
- Saves completed media in `Download/Waslora` and the in-app library.

## iPhone

The iOS source project is an early preview. It supports direct file URLs, an in-app library, M4A conversion, and local Wi-Fi transfer. It is not an installable iOS release; building and signing require Xcode on macOS. Platform-page video downloading is not included in the iOS build.

## Notes

- Android APKs are distributed as standalone downloads. Review Android's install warning and install only Waslora builds you trust.
- Only download and convert content you own or have permission to save.
- The GitHub release is published automatically after its tagged build passes. The attached APK is a debug-signed test build; install it for testing, and uninstall it before installing a build signed with a different key.
