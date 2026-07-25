package dev.yubin.search.indexer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * **원천 창고(PostGIS) → 검색 엔진(ES·Qdrant)** 으로 옮기는 앱 (ADR 0011).
 *
 * 검색 API 와 **따로 뜨는 이유**는 성격이 반대라서다.
 * - 색인: CPU 를 몰아 쓰고(임베딩 추론이 색인 시간의 96.1%), 오래 돌고, 잠깐 죽어도 검색은 살아야 한다.
 * - 질의: 항상 깨어 있고, 저지연이 전부다.
 *
 * 전에는 이 둘이 한 프로세스에 있고 `psp.role.*` 플래그로 빈만 켜고 껐다. 하지만 플래그는
 * **같은 힙을 공유하는 사실**을 바꾸지 못한다 — 색인 쪽 OOM 한 번이 곧 검색 장애였다.
 * 지금은 클래스패스 자체가 다르다. 질의 코드는 여기에 **없다.**
 *
 * `search-core` 를 명시적으로 스캔한다: 문서 스키마·브랜드 규칙·임베딩 모델은 검색 API 와
 * **같은 코드**여야 하고, 그게 core 가 존재하는 이유다.
 */
@SpringBootApplication(scanBasePackages = ["dev.yubin.search.indexer", "dev.yubin.search.core"])
@EnableScheduling   // 색인 lag 지표 주기 갱신
class IndexerBatchApplication

fun main(args: Array<String>) {
	runApplication<IndexerBatchApplication>(*args)
}
