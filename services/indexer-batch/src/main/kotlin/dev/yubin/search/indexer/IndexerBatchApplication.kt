package dev.yubin.search.indexer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["dev.yubin.search.indexer", "dev.yubin.search.core"])
@EnableScheduling
class IndexerBatchApplication

fun main(args: Array<String>) {
	runApplication<IndexerBatchApplication>(*args)
}
