# Magnify (Android)

Kotlin + Jetpack Compose + CameraX + ML Kit (on-device OCR).

## Build the APK
1. Open this folder in Android Studio (Koala or newer, JDK 17). It creates the Gradle wrapper automatically.
2. Build > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk
   (or run `gradle assembleDebug` from the terminal)

## Features
- Live camera magnifier: pinch, -/+ buttons, ruler drag (up to your camera's max zoom)
- Take photo (saved to Pictures/Magnify) then pinch / double-tap to zoom up to 30x
- Search words live in the camera view or inside a photo (on-device OCR, matches highlighted)
- Effects for low-contrast text: High contrast, Invert, Yellow on black, Grayscale
- Auto brightness in low light (exposure is raised automatically); flashlight toggle
- Glass UI, full screen, fluid layout; sidebar button collapses the left panel
