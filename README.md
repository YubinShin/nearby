# place-search-platform

> 내 동네 맛집·카페·놀거리를 찾아주는 검색·추천 서비스 — 그리고 여러 서비스가 공통으로 가져다 쓸 수 있게 만든 "검색 플랫폼"

**상태:** 🟢 설계 완료 · 🟡 핵심 구현 중 · 🗺️ [로드맵](docs/roadmap.md) 공개

---

## 이게 뭔가요

장소를 검색하고 추천받는 서비스예요. 그런데 목표가 "검색 기능 하나 만들기"가 아니에요.
**여러 서비스가 똑같이 가져다 쓸 수 있는 검색·추천 '플랫폼'을, 처음부터 직접 설계해보는 것**이 목표예요.

왜 이런 걸 만드냐면 — 회사에서 하는 검색·추천 일은 코드를 밖에 보여줄 수 없어요.
그래서 제가 실무에서 매일 다루는 문제(검색 엔진 운영, 색인 파이프라인, 키워드+벡터 검색)를
**공개해도 되는 버전으로 똑같이 만들어서, "이런 걸 설계할 줄 안다"를 눈으로 보여주려고** 합니다.

## 무엇을 하나요 (쉽게)

- **키워드로 찾기** — "강남 파스타"처럼 적은 단어를 글자 그대로 매칭해서 찾아요. (Elasticsearch)
- **뜻으로 찾기** — "조용히 얘기하기 좋은 카페"처럼 단어가 딱 안 맞아도 *의미*로 찾아요. (벡터 검색, Qdrant)
- **둘을 합치기** — 위 두 결과를 순위 기준으로 똑똑하게 섞어 최종 결과를 만들어요. (RRF)
- **거리로 좁히기** — 지금 위치에서 가까운 곳 위주로 보여줘요. (PostGIS 지리 검색)
- **추천** — 비슷한 장소, 방금 둘러본 것들의 흐름, 처음 온 사람에겐 '인기 + 가까움'.

## 어떻게 동작하나요

![아키텍처](docs/diagrams/architecture.svg)

큰 흐름은 이래요.

1. 공공데이터(상가정보·관광정보)를 **원천 창고(PostGIS)** 에 모아둬요.
2. 데이터가 **바뀔 때만** 그 부분만 검색 엔진에 반영해요. (전체를 매번 새로 만들지 않아요 → 낭비 줄이기)
3. 사용자가 검색하면, 서버가 **키워드 엔진과 뜻 엔진에 동시에** 물어봐요.
4. 두 답을 하나로 합치고, 거리로 다듬어서 돌려줘요.

더 자세한 그림과 각 조각의 역할은 → [docs/architecture.md](docs/architecture.md)

## 왜 이렇게 만들었나 (설계 결정 기록)

결정마다 "왜 이걸 골랐는지"를 짧은 문서로 남겼어요. **이 문서들이 이 프로젝트의 진짜 알맹이예요.**
(무슨 기술을 썼는지보다, 어떤 문제를 어떤 구조로 풀었는지가 여기 담겨 있어요.)

- [0001 — 데이터를 언제 색인할까: 실시간 말고 '바뀔 때만'](docs/adr/0001-event-triggered-incremental-indexing.md)
- [0002 — 검색 인덱스를 용도별로 나눈 이유](docs/adr/0002-index-and-cluster-separation.md)
- [0003 — 키워드·벡터 결과를 앱에서 합치는 방법 (RRF)](docs/adr/0003-hybrid-search-rrf-in-app-layer.md)
- [0004 — 쿠키 없이 세션 다루기 (프라이버시)](docs/adr/0004-cookieless-session-model.md)
- [0005 — 처음 온 사람에게 뭘 보여줄까 (콜드스타트)](docs/adr/0005-cold-start-and-recommend-strategy.md)
- [0006 — 서버를 리액티브(WebFlux+코루틴)로 짠 이유](docs/adr/0006-api-runtime-reactive-vs-blocking.md)
- [0007 — 벡터 엔진 선택: ES 내장 vs 별도 엔진, Qdrant vs Milvus](docs/adr/0007-vector-engine-qdrant-vs-milvus.md)
- [0008 — 한국어 형태소 분석기: nori vs KOMORAN, 그리고 왜 플러그인으로 직접 통합하나](docs/adr/0008-korean-analyzer-komoran-vs-nori.md)

## 로컬에서 실행하기

```bash
docker compose -f deploy/docker-compose.yml up -d   # 검색 엔진들(ES·Qdrant·Redis·PostGIS)을 한 번에 띄우기
```

## 지금 상태와 앞으로

지금은 **설계를 끝내고 핵심을 만드는 중**이에요. 무엇을 언제까지 할지는 [로드맵](docs/roadmap.md)에 정리해두었어요.

## 기술 스택

Kotlin · Spring Boot(WebFlux) · Kotlin 코루틴 · Elasticsearch · Qdrant · Redis · PostgreSQL/PostGIS · Kubernetes
