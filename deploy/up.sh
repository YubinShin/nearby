#!/usr/bin/env bash
# 로컬 검색 스택 기동 헬퍼.
# KOMORAN 플러그인을 패키징 → ES 커스텀 이미지 빌드 컨텍스트로 복사 → compose up.
#
# 사용:  ./deploy/up.sh [프로파일]        # 기본 gangnam
#        ./deploy/up.sh seoul
#
# 프로파일은 compose 프로젝트 이름이고, 그대로 볼륨 프리픽스가 된다
# (gangnam_es-data / seoul_es-data ...). ES·Qdrant·PostGIS 세 볼륨이 한 덩어리로
# 갈리므로 데이터셋이 어긋나지 않는다. 포트와 container_name 이 겹쳐 동시 기동은 안 된다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PROFILE="${1:-gangnam}"

RUNNING_VOL="$(docker inspect psp-elasticsearch \
  --format '{{range .Mounts}}{{if eq .Destination "/usr/share/elasticsearch/data"}}{{.Name}}{{end}}{{end}}' \
  2>/dev/null || true)"
if [ -n "$RUNNING_VOL" ] && [ "$RUNNING_VOL" != "${PROFILE}_es-data" ]; then
  echo "✖ 다른 프로파일이 떠 있습니다 — $RUNNING_VOL"
  echo "  먼저 내리십시오 (-v 를 주면 볼륨이 지워진다):"
  echo "    docker compose -p ${RUNNING_VOL%%_*} -f deploy/docker-compose.yml down"
  exit 1
fi

echo "▶ 1/4  KOMORAN 플러그인 패키징"
./es-analysis-komoran/gradlew -p es-analysis-komoran pluginZip

echo "▶ 2/4  플러그인 zip을 ES 이미지 빌드 컨텍스트로 복사"
cp es-analysis-komoran/build/distributions/komoran-analysis.zip deploy/elasticsearch/

echo "▶ 3/4  docker compose up — 프로파일 ${PROFILE} (ES 이미지 빌드 포함)"
docker compose -p "$PROFILE" -f deploy/docker-compose.yml up -d --build

echo "▶ 4/4  적재 현황"
for _ in $(seq 1 30); do
  if curl -sf -m 6 -o /dev/null \
    'localhost:9200/_cluster/health?wait_for_status=yellow&timeout=5s'; then
    break
  fi
  sleep 2
done

or_none() { if [ -n "$1" ]; then echo "$1"; else echo "없음 — 재색인 필요"; fi; }

ES_DOCS="$(curl -s -m 3 localhost:9200/place_search/_count 2>/dev/null \
  | sed -n 's/.*"count":\([0-9]*\).*/\1/p' || true)"
VEC_COLL="$(curl -s -m 3 localhost:6333/collections 2>/dev/null \
  | sed -n 's/.*"name":"\(place_vec_[0-9]*\)".*/\1/p' | head -1 || true)"
VEC_DOCS=""
if [ -n "$VEC_COLL" ]; then
  VEC_DOCS="$(curl -s -m 3 "localhost:6333/collections/${VEC_COLL}" 2>/dev/null \
    | sed -n 's/.*"points_count":\([0-9]*\).*/\1/p' || true)"
fi
SRC_ROWS="$(docker exec psp-postgis psql -U place -d place -tAc \
  'SELECT count(*) FROM public.place' 2>/dev/null | tr -d ' ' || true)"

echo
echo "  프로파일   ${PROFILE}   (볼륨 ${PROFILE}_es-data · ${PROFILE}_qdrant-data · ${PROFILE}_postgis-data)"
echo "  원천       $(or_none "$SRC_ROWS")"
echo "  키워드     $(or_none "$ES_DOCS")"
echo "  벡터       $(or_none "$VEC_DOCS")"
echo
echo "✔ 기동 완료. ES 상태:  curl -s localhost:9200/_cat/plugins?v"
echo "  KOMORAN 사용자 사전은 deploy/elasticsearch/analysis/komoran/place.dict 를 마운트해 쓴다."
echo
echo "  ── 색인은 색인기(8081)에 건다. 질의기(8080)에는 관리 API 가 아예 없다 (ADR 0011)."
echo "     재색인은 접수만 하고 즉시 202 + jobId 를 준다 — 진행은 따로 조회한다 (ADR 0013)."
echo
echo "  원천 데이터가 바뀌었다면 사전 재생성 후 재색인:"
echo "    python3 scripts/build_komoran_dict.py"
echo "    curl -XPOST localhost:8081/admin/reindex            # → 202 {\"jobId\":1,...}"
echo "    curl localhost:8081/admin/jobs/1                    # 진행·결과 조회"
echo
echo "  벡터(뜻) 검색을 쓰려면 임베딩 모델(470MB)이 먼저 있어야 한다:"
echo "    ./scripts/fetch_embedding_model.sh"
echo "    curl -XPOST localhost:8081/admin/vector/reindex"
echo "  (벡터 색인은 6만 건에 약 9분 — 임베딩 추론이 병목이다. ADR 0010)"
echo "  이제 curl 을 끊어도 색인은 계속 돈다 — job 스레드에서 돌기 때문이다."
