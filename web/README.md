# web — Nearby 콘솔

`search-api` 와 `ask-api` 를 한 화면에서 보는 개발용 프런트엔드입니다.
Vite + React + TypeScript, 스타일은 CSS Modules 입니다.

| 탭 | 호출 | 보는 것 |
|---|---|---|
| 자연어 질의 | `ask-api` `GET /v1/ask` | LLM 이 뽑은 구조(`parsed`), 실제로 보낸 요청(`applied`), `unmapped`·`unsupported`, degraded, LLM/검색 구간 지연 |
| 채널 비교 | `search-api` `/v1/search` · `/v1/vsearch` · `/v1/hsearch` | 같은 질의의 키워드·벡터·하이브리드 결과와 RRF 배지 |
| 그라운딩 실험 | 없음 (녹화 파일) | `scripts/fixtures/<날짜>/` 의 함정 실험 8종 — 컨텍스트·답변·evidence·검증 결과 |

## Running

```bash
npm install
npm run dev            # http://localhost:5173
```

개발 서버가 프록시합니다. 브라우저는 5173 한 곳만 호출하므로 CORS 설정이 필요 없습니다.

| 경로 | 대상 | 환경변수 |
|---|---|---|
| `/api/search/*` | `http://localhost:8080` | `SEARCH_API` |
| `/api/ask/*` | `http://localhost:8082` | `ASK_API` |

백엔드는 따로 기동합니다.

```bash
./deploy/up.sh gangnam                       # ES · Qdrant · PostGIS
cd services && ./gradlew :search-api:bootRun # 8080

# ask-api 는 API 키가 없으면 기동하지 않습니다. 녹화 응답으로 실행하려면 fixture 모드를 씁니다.
./gradlew :ask-api:bootRun \
  --args='--psp.ask.llm=fixture --psp.ask.fixtures.location=file:src/test/resources/fixtures/'
```

`search-api` 만 띄운 상태에서도 채널 비교 탭과 그라운딩 탭은 동작합니다.
자연어 질의 탭은 `ask-api` 가 없으면 호출 실패를 그대로 표시합니다.

## Fixtures

그라운딩 탭은 `scripts/fixtures/*/*.json` 을 `import.meta.glob` 으로 빌드 시점에 읽습니다.
`python3 scripts/grounding_experiments.py` 로 새 날짜 폴더가 생기면 실행 선택 버튼이 자동으로 늘어납니다.

실험 설명(`note`)과 채점 규칙(`checks`)은 2026-08-05 부터 픽스처에 함께 기록합니다.
그 이전 실행(`260804`)은 `src/features/grounding/fixtures.ts` 의 `FALLBACK_NOTES` 로 설명을 채웁니다.

## Build

```bash
npm run build          # tsc -b && vite build → dist/
```

`dist/` 를 정적 호스팅에 올릴 때는 프록시가 없으므로 API 주소를 빌드 시점에 지정합니다.

```bash
VITE_SEARCH_BASE=http://localhost:8080 VITE_ASK_BASE=http://localhost:8082 npm run build
```