adb install -r C:\dev\general\upload\app-debug.apk
adb shell monkey -p myapp.app -c android.intent.category.LAUNCHER 1
adb logcat -c
adb logcat | Select-String -Pattern "TTS","CreateAudio","PhonemeConverter","KokoroWaveDebug"
