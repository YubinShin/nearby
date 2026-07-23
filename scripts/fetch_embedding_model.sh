#!/usr/bin/env bash
# 임베딩 모델(ONNX) 내려받기 — 5단계 벡터 검색용 (ADR 0010)
#
#   ./scripts/fetch_embedding_model.sh
#
# 왜 스크립트인가:
#   모델 가중치는 470MB 짜리 **바이너리 산출물**이라 git 에 넣지 않는다.
#   KOMORAN 사전(scripts/build_komoran_dict.py)과 같은 취급 — 재현 방법만 저장소에 둔다.
#
# 왜 이 모델인가 (intfloat/multilingual-e5-small):
#   - 다국어(한국어 포함) 학습 · 384차원 · 118M 파라미터로 노트북 CPU 에서 돌릴 수 있는 크기
#   - ONNX 파일이 공식 배포돼 파이썬 없이 JVM 에서 바로 추론 가능
#   자세한 비교는 docs/adr/0010-embedding-model-and-serving.md 참고.
set -euo pipefail

REPO="intfloat/multilingual-e5-small"
DEST="$(cd "$(dirname "$0")/.." && pwd)/models/multilingual-e5-small"
BASE="https://huggingface.co/${REPO}/resolve/main"

mkdir -p "$DEST"

# tokenizer.json 은 루트에, onnx 는 onnx/ 아래에 있다. 앱은 둘 다 한 폴더에서 찾는다.
fetch() { # <원격 경로> <로컬 파일명>
	local remote="$1" local_name="$2" target="$DEST/$2"
	if [ -s "$target" ]; then
		echo "  이미 있음: $local_name ($(du -h "$target" | cut -f1))"
		return
	fi
	echo "  받는 중: $local_name ..."
	curl -fL --progress-bar -o "$target.tmp" "$BASE/$remote"
	mv "$target.tmp" "$target"
}

echo "임베딩 모델 받기 → $DEST"
fetch "onnx/model.onnx" "model.onnx"
fetch "tokenizer.json"  "tokenizer.json"
fetch "config.json"     "config.json"

echo
echo "완료. 앱이 이 경로를 못 찾으면 환경변수로 알려주세요:"
echo "  export PSP_MODEL_DIR=$DEST"
