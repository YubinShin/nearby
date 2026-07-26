package dev.yubin.search

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SearchApiApplication

fun main(args: Array<String>) {
	runApplication<SearchApiApplication>(*args)
}
