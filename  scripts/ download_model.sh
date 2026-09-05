#!/usr/bin/env bash
# Download MobileFaceNet TFLite model into app/src/main/assets/
#
# Model : MobileFaceNet (192-d output, trained on MS-Celeb-1M)
# Source: https://github.com/sirius-ai/MobileFaceNet_TF
#
# Alternative: FaceNet 128-d from shubham0204
#   https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android
#   If using this, set EMBEDDING_DIM = 128 in FaceEmbedder.kt

set -e

ASSETS_DIR="$(dirname "$0")/../app/src/main/assets"
MODEL_NAME="mobilefacenet.tflite"
MODEL_PATH="$ASSETS_DIR/$MODEL_NAME"

mkdir -p "$ASSETS_DIR"

if [ -f "$MODEL_PATH" ]; then
    echo "Model already present at $MODEL_PATH"
    exit 0
fi

echo "Downloading MobileFaceNet TFLite model..."

# Try primary source
MODEL_URL="https://github.com/yeanzhi/MobileFaceNet_TF/releases/download/v1.0/mobilefacenet.tflite"
if curl -L --fail "$MODEL_URL" -o "$MODEL_PATH" 2>/dev/null; then
    echo "Downloaded from primary source."
else
    echo "Primary URL failed. Trying FaceNet 128-d alternative..."
    ALT_URL="https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android/raw/master/app/src/main/assets/facenet.tflite"
    curl -L --fail "$ALT_URL" -o "$MODEL_PATH"
    echo "NOTE: Using FaceNet 128-d. Change EMBEDDING_DIM = 128 in FaceEmbedder.kt"
fi

echo "Done. Model saved: $MODEL_PATH ($(du -h "$MODEL_PATH" | cut -f1))"