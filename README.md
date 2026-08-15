# Hotspot Drop

Android app that turns your phone into a local file drop point.

When you enable your phone hotspot and start the server in the app, any device connected to that hotspot can open the shown URL in a browser and upload files to the phone or download shared files from it.

## What It Does

- Starts a local HTTP server on the phone
- Advertises a `.local` hostname for easier local access
- Shows the local hostname and IP fallback URLs
- Accepts drag and drop uploads from another device browser
- Lists shared files for download from connected devices
- Saves uploaded files into `Downloads/HotspotDrop`

## Project Notes

- Platform: Android
- Language: Kotlin
- Min Android version: 10 (API 29)
- HTTP server: NanoHTTPD

## Open And Run

1. Open the folder in Android Studio.
2. Let Android Studio sync the Gradle project.
3. Run the app on an Android phone.
4. Turn on your phone hotspot.
5. Tap `Start server` in the app.
6. From a connected device, open the displayed `.local` URL in a browser. If needed, use the IP fallback URL.
7. Drag a file onto the page to upload, or download any file listed on the page.

## Current Limitation

- This workspace does not include a Gradle wrapper because `gradle` is not installed in the current environment.
- The app files are ready for Android Studio import and Gradle sync.
