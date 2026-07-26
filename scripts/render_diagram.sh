#!/usr/bin/env bash
# 다이어그램(.dc.html)을 PNG 로 다시 만든다.
#
#   전제:  Google Chrome 설치
#   실행:  ./scripts/render_diagram.sh [이름]     # 기본값 architecture
#          ./scripts/render_diagram.sh deploy
#   결과:  docs/diagrams/<이름>.png  (원본 캔버스의 2배 해상도)
#
# 왜 스크립트로 두나 — 그림 파일만 커밋해 두면 나중에 "이걸 어떻게 만들었지"가 된다.
# 원본(`<이름>.dc.html`)과 로고(`uploads/`)를 함께 두고 여기서 재생성한다.
#
# 원본은 Claude Design 에서 편집한 뒤 내려받아 `docs/diagrams/<이름>.dc.html` 로
# 덮어쓰면 된다. 그 파일은 Design 런타임용 태그(`<x-dc>`·`{{accent}}`)를 담고 있어서,
# 이 스크립트가 그것들을 걷어내고 순수 HTML 로 만든 뒤 렌더링한다.
set -euo pipefail

cd "$(dirname "$0")/.."
NAME="${1:-architecture}"
SRC="docs/diagrams/${NAME}.dc.html"
OUT="docs/diagrams/${NAME}.png"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

[ -f "$SRC" ] || { echo "원본이 없습니다: $SRC" >&2; exit 1; }
[ -x "$CHROME" ] || { echo "Chrome 을 찾을 수 없습니다: $CHROME (CHROME 환경변수로 지정 가능)" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 로고는 원본이 `uploads/...` 상대경로로 참조하므로 같은 구조로 옮겨둔다.
cp -R docs/diagrams/uploads "$WORK/uploads"

# 캔버스 크기를 원본에서 읽고, Design 런타임 태그를 걷어낸 순수 HTML 을 만든다.
# 크기를 여기 하드코딩하지 않는 이유: 원본에서 캔버스를 바꿔도 스크립트를 안 고치게.
SIZE="$(python3 - "$SRC" "$WORK/render.html" <<'PY'
import re, sys, pathlib

src, dst = sys.argv[1], sys.argv[2]
t = pathlib.Path(src).read_text(encoding="utf-8")

m = re.search(r'width:(\d+)px;\s*height:(\d+)px;\s*background:#ffffff', t)
if not m:
    raise SystemExit("캔버스 크기를 못 찾았습니다 — 원본의 최상위 div 스타일을 확인하세요")

# Claude Design 런타임을 걷어낸다 — 렌더 결과가 런타임 버전에 흔들리지 않게.
t = t.replace('<script src="./support.js"></script>', '')
t = t.replace('<script src="./image-slot.js"></script>', '')
t = t.replace('<x-dc>', '').replace('</x-dc>', '')
t = t.replace('<helmet>', '').replace('</helmet>', '')
t = re.sub(r'<script type="text/x-dc".*?</script>', '', t, flags=re.S)

# 프로퍼티 기본값을 확정한다. Design 에서 고른 값과 같아야 그림이 일치한다.
t = t.replace('{{accent}}', '#2563eb').replace('{{labelDisp}}', 'block')

if '{{' in t:
    raise SystemExit("치환되지 않은 템플릿 변수가 남았습니다 — 이 스크립트를 갱신하세요")

pathlib.Path(dst).write_text(t, encoding="utf-8")
print(m.group(1), m.group(2))
PY
)"
read -r W H <<< "$SIZE"

# --force-device-scale-factor=2 로 2배 해상도. 문서 임베드용이라 벡터가 아니어도 충분하다.
# --virtual-time-budget: 웹폰트(Noto Sans KR)를 받아올 시간을 준다. 네트워크가 없으면
#   시스템 한글 폰트로 떨어지므로 글자 모양이 조금 달라진다.
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --no-sandbox \
  --window-size="$W,$H" --force-device-scale-factor=2 \
  --virtual-time-budget=8000 \
  --screenshot="$WORK/out.png" "file://$WORK/render.html" 2>/dev/null

cp "$WORK/out.png" "$OUT"
echo "생성 완료: $OUT ($((W * 2))x$((H * 2)))"
