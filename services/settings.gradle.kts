rootProject.name = "nearby"

/**
 * 검색 애플리케이션들의 **멀티프로젝트 빌드**.
 *
 * 원래는 `search-api` 하나였다. 한 아티팩트 안에서 `psp.role.indexer` / `psp.role.query` 라는
 * 런타임 플래그로 색인 노드와 질의 노드를 나눠 띄웠는데, 나눠 띄워도 **한 아티팩트라는 사실**은
 * 그대로였다. 색인 쪽이 OOM 으로 죽으면 그건 곧 검색 장애다.
 *
 * 그래서 런타임 플래그로 나누던 역할을 **빌드 시점 경계로 승격**했다 (ADR 0011).
 * 여기서 include 되지 않은 모듈은 그 앱의 클래스패스에 아예 없다 — 이게 플래그와 다른 점이다.
 */
include("search-core")
include("search-api")
include("indexer-batch")
include("indexer-stream")
