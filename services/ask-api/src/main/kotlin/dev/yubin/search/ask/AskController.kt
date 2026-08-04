package dev.yubin.search.ask

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class AskController(private val ask: AskService) {
	@GetMapping("/ask")
	suspend fun ask(
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false) size: Int?,
		@RequestParam(required = false) lat: Double?,
		@RequestParam(required = false) lon: Double?,
	): AskResponse = ask.ask(q = q, size = size, lat = lat, lon = lon)
}
