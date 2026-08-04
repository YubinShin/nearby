package dev.yubin.search.ask

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException

@RestController
@RequestMapping("/v1")
class AskController(private val ask: AskService) {
	@GetMapping("/ask")
	suspend fun ask(
		@RequestParam q: String,
		@RequestParam(required = false) size: Int?,
		@RequestParam(required = false) lat: Double?,
		@RequestParam(required = false) lon: Double?,
	): AskResponse {
		if (q.isBlank()) {
			throw ServerWebInputException("q must not be blank")
		}
		return ask.ask(q = q, size = size, lat = lat, lon = lon)
	}
}
