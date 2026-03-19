#!/bin/bash
# setup.sh - venv の作成と依存パッケージのインストール

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# venv が存在しない場合は作成
if [ ! -d "venv" ]; then
    echo "Creating virtual environment..."
    python -m venv venv
fi

# venv を有効化
source venv/Scripts/activate 2>/dev/null || source venv/bin/activate

echo "Installing dependencies..."
pip install --upgrade pip
pip install flask opencv-python qrcode pillow

echo ""
echo "Setup complete. Run './start.sh' to launch the app."
