<div align="center">



# FaceCollage

**Detect · Identify · Collage**

*Portrait video → unique faces → Instagram Story collage. Entirely on-device.*

<br/>

[![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![ML Kit](https://img.shields.io/badge/ML%20Kit-Face%20Detection-4285F4?style=for-the-badge&logo=google&logoColor=white)](https://developers.google.com/ml-kit)
[![TFLite](https://img.shields.io/badge/TFLite-FaceNet%20512d-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white)](https://tensorflow.org/lite)
[![License](https://img.shields.io/badge/License-MIT-lightgrey?style=for-the-badge)](LICENSE)

<br/>

[▶️ &nbsp;**Watch Demo**](https://drive.google.com/file/d/1k4KbGFueo99HyD1P-ldB6RKF5JlCZRiL/view?usp=sharing)&nbsp;&nbsp;&nbsp;•&nbsp;&nbsp;&nbsp;[⬇️ &nbsp;**Download APK**](https://drive.google.com/file/d/1-z356IN2i0DTbmBvSNTEslXoRggjqCrh/view?usp=sharing)&nbsp;&nbsp;&nbsp;•&nbsp;&nbsp;&nbsp;[📖 &nbsp;**Setup Guide**](#-build--setup)

</div>

---

## 🎬 What It Does

FaceCollage processes any portrait video on-device through a 5-stage ML pipeline:

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   Video  ──▶  Detect  ──▶  Embed  ──▶  Cluster  ──▶  Collage  │
│                                                                 │
│   Any MP4     ML Kit      FaceNet     DBSCAN      1080×1920    │
│   portrait    accurate    TFLite      cosine       Instagram    │
│   video       mode        512-d       distance     Story fmt    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

No internet. No backend. No data leaves the device.

---

## ✨ Features

<table>
<tr>
<td width="50%">

**🔍 Detection**
- ML Kit accurate mode
- Landmarks + classification
- Laplacian blur rejection
- Whip-pan frame filtering

</td>
<td width="50%">

**🧠 Identity**
- FaceNet 512-d embeddings
- DBSCAN auto-clustering
- No k pre-specification
- Cosine distance metric

</td>
</tr>
<tr>
<td width="50%">

**⭐ Quality Scoring**
- Head pose frontality
- Sharpness (Laplacian)
- Eyes open probability
- Smile + frame coverage

</td>
<td width="50%">

**🖼️ Collage**
- 1080×1920 Story format
- Full-frame tiles (no tight crop)
- Per-person ×N badges
- Save to gallery + share sheet

</td>
</tr>
</table>

---

## 🏗️ Architecture

```
com.facecollage/
│
├── 📱 MainActivity.kt              Single-activity Compose host
├── 🏛️ FaceCollageApp.kt           Hilt application
│
├── di/
│   └── AppModule.kt               Singleton providers
│
├── domain/
│   ├── model/Models.kt            DetectedFace · FaceQualityScore
│   │                              PersonCluster · ProcessingState
│   └── usecase/
│       └── ProcessVideoUseCase.kt Pipeline orchestrator (channelFlow)
│
├── ml/
│   ├── VideoFrameExtractor.kt     MediaMetadataRetriever · OPTION_CLOSEST
│   ├── FaceDetectorWrapper.kt     ML Kit · quality scoring
│   ├── FaceEmbedder.kt            FaceNet TFLite · NNAPI
│   ├── FaceClusterer.kt           DBSCAN · appearance segmentation
│   └── CollageRenderer.kt         Canvas · 1080×1920
│
├── ui/
│   ├── MainViewModel.kt           Hilt ViewModel · StateFlow
│   ├── screens/MainScreen.kt      Jetpack Compose
│   └── theme/Theme.kt             Material3 dark purple
│
└── utils/
    └── ImageSaver.kt              MediaStore · FileProvider
```

---

## 🤖 Embedding Model

| | |
|---|---|
| **Model** | FaceNet |
| **Source** | [shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android) |
| **Asset** | `app/src/main/assets/mobilefacenet.tflite` |
| **Input** | `160 × 160` RGB · normalised to `[−1, 1]` |
| **Output** | `512-d` L2-normalised embedding vector |
| **Runtime** | TFLite + NNAPI delegate + 2 CPU threads |

---

## 🎯 Similarity Threshold

**Threshold: `0.25` cosine distance** &nbsp;→&nbsp; `FaceClusterer.SIMILARITY_THRESHOLD`

```
0.0 ──────────────┬──────────────┬──────────────┬────────── 2.0
                0.10           0.25           0.50
                  │              │              │
              Same person    THRESHOLD     Different
              high conf.     cutoff        people
              ◀──────────────▶              ◀──────────
                 INCLUDED                  EXCLUDED
```

> Tuned against Sample 1 ground truth: **5 people × 4 appearances = 20 total**

---

## ⭐ Representative Shot Formula

```
composite score  =  frontality    × 0.35   ← how front-facing (eulerY, eulerX)
                 +  sharpness     × 0.30   ← Laplacian variance (in-focus check)
                 +  eyesOpen      × 0.20   ← both eyes open (ML Kit)
                 +  frameCoverage × 0.10   ← face not clipped at edges
                 +  smiling       × 0.05   ← smile probability (ML Kit)
```

The detection with the **highest composite score** per cluster becomes the collage tile.
Tiles show the **full video frame** center-cropped — never a tight face crop.

---

## 👁️ Appearance Counting

```
Timeline:  ████░░░░████░░░░░░░░░████░░░░████
                 ↑                         ↑
             gap > 1500ms          gap > 1500ms
             = new appearance      = new appearance

Result: 4 appearances for this person
```

- Gap ≤ 1500 ms between detections → **same appearance**
- Gap > 1500 ms → **new appearance starts**
- Blurred / whip-pan frames → rejected by sharpness score before reaching clusterer

---

## 🚀 Build & Setup

### Prerequisites

| Requirement | Version |
|---|---|
| Android Studio | Hedgehog `2023.1.1`+ |
| JDK | `17` (use AS bundled JDK) |
| Device / Emulator | API `26`+ |

### 1 · Clone

```bash
git clone https://github.com/AtharvKhunte/FaceCollage.git
cd FaceCollage
```

### 2 · Download the model

```bash
# Download from:
https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android/raw/master/app/src/main/assets/facenet.tflite

# Rename to mobilefacenet.tflite
# Place at:
app/src/main/assets/mobilefacenet.tflite
```

### 3 · Sync Gradle

```
Android Studio → File → Sync Project with Gradle Files
```

### 4 · Build

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

```
Output → app/build/outputs/apk/debug/app-debug.apk
```

### 5 · Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 📦 Dependencies

| Library | Version | Role |
|---|---|---|
| ML Kit Face Detection | `16.1.7` | Detection · landmarks · classification |
| TensorFlow Lite | `2.16.1` | FaceNet inference |
| TFLite GPU delegate | `2.16.1` | Hardware acceleration |
| Jetpack Compose BOM | `2024.09.03` | UI |
| Hilt | `2.52` | Dependency injection |
| KSP | `2.0.21-1.0.25` | Annotation processing |
| Coil | `2.7.0` | Image loading |
| WorkManager | `2.9.1` | Background tasks |
| Kotlin Coroutines | `1.9.0` | Async · Flow |

---

## ⚠️ Known Limitations

- ML Kit may miss faces in poor lighting or extreme angles
- DBSCAN is O(n²) — suits clips up to ~10 min at 15 fps
- Threshold `0.25` tuned for FaceNet-512d specifically
- Very similar-looking people may occasionally merge into one cluster

---

<div align="center">

**Built for the iykyk Android Internship Assignment · September 2026**

*All processing is on-device. No data ever leaves your phone.*

</div>
