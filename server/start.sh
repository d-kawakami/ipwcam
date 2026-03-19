#!/bin/bash
# start.sh - Flask アプリの起動

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# venv が存在するか確認
if [ ! -d "venv" ]; then
    echo "venv が見つかりません。先に './setup.sh' を実行してください。"
    exit 1
fi

# venv を有効化
source venv/Scripts/activate 2>/dev/null || source venv/bin/activate

echo "Starting Flask app on http://0.0.0.0:5500 ..."
echo "ブラウザで http://localhost:5500/video を開いてください"
echo ""
python app.py
