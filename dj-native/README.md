# dj-native: audio analysis in C++

Finds tempo, beat grid, bars, key, loudness, energy, sections and chorus candidates in a track. One
JNI function in, one JSON string out. Results are read in Kotlin as `AnalysisResult` (`dj-core`).

## Layout

```
src/main/cpp/
  core/                portable C++17: no JNI, no Android. The part to reuse on other platforms
    abcd_analysis.h      the C API (abcd_analyze_json) - the only header a host needs
    analyzer.cpp         pipeline: resample -> Essentia tempo/key -> bars -> per-bar features
    structure.cpp        self-similarity matrix, repetition, chorus candidates, sections, phrases
    dsp.cpp              resampler and BS.1770 loudness
    json_writer.cpp      the wire format
  jni/abcd_jni.cpp     Android glue (thin)
  dev_tools/           command-line analyser + synthetic-song self test (not in the app)
  essentia_generated/  registry and version.h generated from Essentia (committed)
third_party/essentia   Essentia, AGPL-3.0 (git submodule)
third_party/eigen      Eigen 3.4.0, MPL-2.0, header-only (git submodule)
scripts/generate_essentia_registry.py
src/main/kotlin/.../NativeAnalyzer.kt   the Kotlin side of the JNI call
```

## Porting to another platform later

Compile `core/` plus the Essentia subset (see `CMakeLists.txt`) and call `abcd_analyze_json`. Nothing
else is needed: decoding to PCM is the platform's job (`PcmDecoder` in `dj-core`; on Android
`MediaCodecPcmDecoder`). The JSON layout is defined by `core/json_writer.cpp` and parsed by
`AnalysisResult.fromWireJson`.

## Setup

```
git submodule update --init dj-native/third_party/essentia dj-native/third_party/eigen
```

Gradle builds `arm64-v8a`, `armeabi-v7a` and `x86_64` through CMake (`:dj-native:assembleDebug`).

## Testing without a phone (Android emulator)

Build the command-line tool for the emulator's ABI and run it there:

```
cmake -S dj-native/src/main/cpp -B dj-native/build/cli-x86_64 -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE=%ANDROID_HOME%/ndk/<ver>/build/cmake/android.toolchain.cmake ^
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release -DABCD_BUILD_TOOLS=ON
cmake --build dj-native/build/cli-x86_64 --target abcd_analyze_cli
adb push dj-native/build/cli-x86_64/abcd_analyze_cli /data/local/tmp/ && adb shell chmod +x /data/local/tmp/abcd_analyze_cli
adb shell /data/local/tmp/abcd_analyze_cli --selftest
```

`--selftest` analyses a generated song with known tempo (126 BPM), key (A minor) and choruses
(bars 12-19, 28-35, 40-47) and checks the answers. It also takes `--wav file.wav`, `--raw file.f32
RATE`, `--write-synth-wav out.wav` and `--json`.

The full chain (MediaCodec decode, JNI, parsing) is covered by
`androidApp/src/androidTest/.../DjAnalysisInstrumentedTest.kt`. Push `abcd_synth.wav` (from
`--write-synth-wav`) and optionally `abcd_song.<ext>` to
`/sdcard/Android/data/in.aiqulabs.abcd.debug/files/`, then:

```
gradlew :androidApp:installDebug :androidApp:installDebugAndroidTest
adb shell am instrument -w -e class com.aiqulabs.abcd.dj.DjAnalysisInstrumentedTest ^
  in.aiqulabs.abcd.debug.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s AbcdAnalysis:I
```

(`gradlew connectedDebugAndroidTest` reinstalls the app and deletes the pushed files.)

## Changing the Essentia algorithm set

Edit `ROOT_ALGORITHMS` in `scripts/generate_essentia_registry.py`, run it, commit the files in
`essentia_generated/`. Bump `ABCD_ANALYSIS_VERSION` in `core/abcd_analysis.h` when results change.
