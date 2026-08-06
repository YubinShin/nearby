# Architecture Decision Records

코드에는 주석을 두지 않습니다. 각 결정의 배경은 해당 ADR 과 커밋 메시지에 있습니다.

각 문서 끝의 "구현 위치" 표에 있는 커밋 해시는 `git show <해시>` 로 확인합니다. 해시는 그 결정이 반영된 시점의 커밋이며 파일의 최신 상태가 아닙니다. 코드 경로는 `services/<모듈>/src/{main,test}/kotlin/dev/yubin/search/` 이하입니다.

| ADR | 제목 |
|---|---|
| [0001](0001-event-triggered-incremental-indexing.md) | 이벤트 트리거 증분 색인을 선택 |
| [0002](0002-index-and-cluster-separation.md) | 용도별 인덱스·클러스터 분리 |
| [0003](0003-hybrid-search-rrf-in-app-layer.md) | 키워드·벡터 결과를 앱 레이어에서 RRF 로 결합 |
| [0004](0004-cookieless-session-model.md) | 쿠키리스 세션 모델 |
| [0005](0005-cold-start-and-recommend-strategy.md) | 콜드스타트 추천을 인기·거리 기반으로 구성 |
| [0006](0006-api-runtime-reactive-vs-blocking.md) | 질의 API 런타임으로 리액티브(WebFlux + 코루틴)를 선택 |
| [0007](0007-vector-engine-qdrant-vs-milvus.md) | 벡터 엔진으로 별도 엔진 Qdrant 를 선택 |
| [0008](0008-korean-analyzer-komoran-vs-nori.md) | 한국어 형태소 분석기로 KOMORAN 을 선택, ES 플러그인으로 직접 통합 |
| [0009](0009-keyword-ranking-and-fallback.md) | 키워드 랭킹은 정밀도 우선, 0건 시 완화 폴백 |
| [0010](0010-embedding-model-and-serving.md) | 임베딩 모델 선택과 추론 위치 |
| [0011](0011-module-split-and-index-contract.md) | 색인기와 질의기를 별도 아티팩트로 분리, 색인 계약은 런타임에 대조 |
| [0012](0012-manifests-in-monorepo.md) | 배포 매니페스트를 소스와 같은 저장소에 배치 (모노레포) |
| [0013](0013-indexer-runtime-spring-batch.md) | 색인기 런타임을 리액티브에서 Spring Batch + 블로킹으로 전환 |
| [0014](0014-ask-api-llm-query-understanding.md) | 자연어 질의 이해를 별도 모듈 `ask-api` 에, 검색은 HTTP 로 호출 |
| [0015](0015-ask-api-grounded-answer-generation.md) | 검색 결과를 근거로 한 답변 생성(RAG)을 `ask-api` 에 추가 |
