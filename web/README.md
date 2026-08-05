# web — Nearby Console

`search-api`와 `ask-api`를 함께 사용하는 개발용 웹 애플리케이션입니다.

* **Framework:** Vite + React + TypeScript
* **Styling:** CSS Modules

## Features

| Tab              | API                                        | Description                                                            |
| ---------------- | ------------------------------------------ | ---------------------------------------------------------------------- |
| Natural Language | `GET /v1/ask`                              | `parsed`, `applied`, `unmapped`, `unsupported`, degraded 상태, LLM/검색 지연 |
| Search Channels  | `/v1/search`, `/v1/vsearch`, `/v1/hsearch` | 키워드·벡터·하이브리드 결과와 RRF 점수 비교                                             |
| Grounding        | Recorded fixtures                          | `scripts/fixtures/<date>/`의 실험 결과, evidence, 검증 결과                     |

## Running

### Web

```bash
npm install
npm run dev
```

기본 주소: `http://localhost:5173`

| Path            | Target                  | Environment  |
| --------------- | ----------------------- | ------------ |
| `/api/search/*` | `http://localhost:8080` | `SEARCH_API` |
| `/api/ask/*`    | `http://localhost:8082` | `ASK_API`    |

### Backend

```bash
# search-api
./deploy/up.sh gangnam
cd services
./gradlew :search-api:bootRun

# ask-api (fixture mode)
./gradlew :ask-api:bootRun \
  --args='--psp.ask.llm=fixture --psp.ask.fixtures.location=file:src/test/resources/fixtures/'
```

## Fixtures

Grounding 결과는 다음 명령으로 녹화합니다.

```bash
python3 scripts/grounding_experiments.py
```

생성된 JSON은 `scripts/fixtures/`에서 확인할 수 있습니다.

## Build

```bash
npm run build
```

정적 호스팅에서는 빌드 시 API 주소를 지정합니다.

```bash
VITE_SEARCH_BASE=http://localhost:8080 \
VITE_ASK_BASE=http://localhost:8082 \
npm run build
```
