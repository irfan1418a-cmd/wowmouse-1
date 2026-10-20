# Wow Mouse (Android Studio project)

Turns your watch into a Bluetooth mouse. Tilt/turn your hand to move the pointer,
shake (or press CLICK) to click.

## Build the APK in the cloud (no Android Studio needed)
1. Make a free account at github.com and create a new repository (private is fine).
2. Click "uploading an existing file" and drag in EVERYTHING from this folder, including the hidden
   ".github" folder. If ".github" will not upload, use "Add file > Create new file", type
   `.github/workflows/build.yml` as the name, and paste the contents of that file.
3. Commit. Open the "Actions" tab; the "Build APK" job starts by itself and takes about 5 minutes.
4. Open the finished run, scroll to "Artifacts", and download "wowmouse-apk". Unzip it to get app-debug.apk.
5. Rename it to wowmouse.apk and put it on the watch.

## Build the APK with Android Studio (needs a stronger computer)
1. Install Android Studio and open this folder (File > Open > WowMouse).
2. Wait for "Gradle sync" to finish. If it asks, accept Gradle 8.7 and JDK 17 (the bundled JDK is fine).
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. The file is at app/build/outputs/apk/debug/app-debug.apk. Rename it to wowmouse.apk.
5. Put it on the watch (for example through Google Drive) and install it.

## Use it
1. Open Wow Mouse on the watch and allow the Bluetooth permissions.
2. Tap "Make discoverable".
3. On the computer/phone/TV, open Bluetooth settings and pair with the watch.
4. If it does not connect by itself, tap "Connect to device" on the watch and pick it.
5. Move your hand to move the pointer. Shake or tap CLICK to click.
6. If left/right or up/down feels backwards, use the Flip buttons.
7. Hold the HOLD button to freeze the pointer while you re-position your hand.

## Notes
- Needs Android 9 (API 28) or newer, a gyroscope, and a watch that allows the Bluetooth HID device profile.
  Some cheap watches do not allow it; the app shows "This watch does not allow a Bluetooth mouse".
- The app has to stay open on the watch screen while you use it.
- This code was not compiled or tested on a real watch when it was written. Report any error text you get.
