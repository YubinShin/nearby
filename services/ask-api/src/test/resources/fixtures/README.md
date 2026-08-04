# fixtures — LLM 응답 재생용

`FixtureLlmClient` 가 읽는 디렉토리입니다. CI 는 실제 API 를 부르지 않고 여기 있는 파일로 돕니다.

| 파일 | 내용 |
|---|---|
| `index.json` | 질의 → 파일명 매핑 + `promptVersion` · 항목별 `model` · 항목별 `promptVersion` |
| `<sha256 앞 12자>.json` | Gemini `generateContent` 응답 **원문** |

파일명은 `sha256(NFC(질의))` 의 앞 12자입니다. 한국어 파일명이 macOS 에서 NFD 로 저장되는
문제를 피하려고 해시를 씁니다.

## 지금 들어 있는 것

27건 전부 `source: recorded` 이고 `gemini-3.5-flash` 로 녹화했습니다(2026-08-04).
골든셋 25건과, 골든셋에 없지만 단위 테스트가 쓰는 2건(`역삼동 조용히 공부할 곳` ·
`강남역 500m 안에 편의점`)입니다.

`record_llm_fixtures.py` 는 항목의 `model` 또는 `promptVersion` 이 지금 값과 다르면 다시 녹화합니다.
골든셋에 없지만 인덱스에 있는 항목도 대상에 포함합니다.

## 프롬프트를 바꾸면

`prompt/ask-parse.json` 의 `version` 을 올리고 다시 녹화합니다. 항목별로 판단하므로 `--force` 는
필요하지 않고, 일부가 실패하면 다음 실행이 실패분만 부릅니다.

모든 항목이 새 버전으로 녹화되기 전까지 인덱스의 `promptVersion` 은 올라가지 않습니다.
버전이 어긋나면 `FixtureLlmClient` 가 기동 로그에 경고를 남기고 어긋난 질의를 나열합니다.
