package dev.yubin.search

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * **질의 전용** 앱 — 읽기만 한다 (ADR 0011).
 *
 * 색인 코드는 여기 없다. 플래그로 꺼둔 게 아니라 `indexer-batch` 라는 **다른 아티팩트**에 있다.
 * 그래서 이 프로세스에는 PostGIS 커넥션도, `/admin` 진입점도, bulk 색인 버퍼도 존재하지 않는다.
 * 색인기가 임베딩 추론으로 힙을 태우다 OOM 으로 죽어도 검색은 계속 답한다 — 그게 분리의 이유다.
 *
 * `dev.yubin.search` 에 있어 `dev.yubin.search.core`(공유 계약)까지 자동으로 스캔한다.
 */
@SpringBootApplication
class SearchApiApplication

fun main(args: Array<String>) {
	runApplication<SearchApiApplication>(*args)
}
