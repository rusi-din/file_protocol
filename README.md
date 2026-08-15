# Hotspot Drop

Android app that turns your phone into a local file drop point.

When you enable your phone hotspot and start the server in the app, any device connected to that hotspot can open the shown URL in a browser and upload a file to the phone.

## What It Does

- Starts a local HTTP server on the phone
- Shows the reachable local URL
- Accepts drag and drop uploads from another device browser
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
6. From a connected device, open the displayed `http://...` URL.
7. Drag a file onto the page or choose a file to upload.

## Current Limitation

- This workspace does not include a Gradle wrapper because `gradle` is not installed in the current environment.
- The app files are ready for Android Studio import and Gradle sync.
