package dev.yubin.search

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 한 아티팩트, 두 역할. `psp.role.indexer` / `psp.role.query` 로 색인 노드와 질의 노드를 나눠 띄운다.
 * 둘은 부하 특성이 다르므로(색인=배치·무거움, 질의=고빈도·저지연) 배포 단위를 분리할 수 있어야 한다.
 */
@SpringBootApplication
@EnableScheduling   // 색인 lag 지표 주기 갱신
class SearchApiApplication

fun main(args: Array<String>) {
	runApplication<SearchApiApplication>(*args)
}
