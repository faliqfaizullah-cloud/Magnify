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

## v1.1
- Phone layout (<600dp wide): big camera card with shutter button, small cards below, side panel becomes a slide-in drawer. Tablets/landscape keep the side-by-side layout.
- 44dp+ touch targets, larger text, keyboard-aware (search field stays visible), press-scale animation.
- Haptics: tap, zoom ticks, zoom-limit buzz, shutter, photo-ready and text-found confirmation.
- v1.2: new app icon (your artwork, transparent background, adaptive).

## v1.4 – Magnifier-style UI
- Full-screen camera, round flip-camera / flashlight buttons, − [shutter] + (hold to keep zooming)
- Bottom bar: filters button, "Find text" pill with voice search, photo-picker button
- Filters sheet: Filters / Contrast / Brightness tabs, live thumbnails, More settings
- Search highlights matching words on the live view, on captured photos and on photos picked from the gallery
