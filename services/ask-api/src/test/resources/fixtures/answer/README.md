# fixtures/answer — 답변 생성 응답 재생용

`FixtureLlmClient.answer` 가 읽는 디렉토리입니다. CI 는 실제 API 를 부르지 않습니다.

| 파일 | 내용 |
|---|---|
| `index.json` | 지문 → 파일명 매핑 + 항목별 `experiment` · `question` · `source` |
| `<sha256 앞 12자>.json` | Gemini `generateContent` 응답 원문 |

키는 `sha256(NFC(질문) + "\n---\n" + NFC(렌더된 컨텍스트))` 의 앞 12자입니다.
질문만으로는 부족합니다 — `회 먹을 데 있어?` 하나가 컨텍스트에 따라 세 항목(`baseline` ·
`empty_context` · `garbage_input`)으로 갈립니다.

키에 컨텍스트가 들어가므로 `AnswerContext.render` 가 녹화 당시와 한 글자라도 다르면
조회가 실패합니다. 렌더러와 녹화본의 일치는 `AskAnswerMappingTest` 가 검증합니다.

## 지금 들어 있는 것

8건 전부 `source: recorded` 이고 `gemini-3.5-flash` 로 녹화했습니다(2026-08-04).
`scripts/grounding_experiments.py` 의 함정 8개이고, 원본은 `scripts/fixtures/260804/` 입니다.

| experiment | 질문 | 컨텍스트 |
|---|---|---|
| `baseline` | 회 먹을 데 있어? | 횟집 5건 |
| `implicit_condition` | 맛있고 가까운 회 먹을 데 있어? | 횟집 5건 |
| `empty_context` | 회 먹을 데 있어? | 0건 |
| `false_premise` | 어방참치 초밥 맛있기로 유명하죠? | 횟집 5건 |
| `knowledge_leak_famous` | 스타벅스 어때? | 스타벅스 5건 |
| `knowledge_leak_unknown` | 어방참치 어때? | 횟집 5건 |
| `context_mismatch` | 어방참치 어때? | 스타벅스 5건 |
| `garbage_input` | 회 먹을 데 있어? | 횟집 5건 + 회계사무소 1건 |

`scripts/fixtures/260804/` 에서 응답 원문만 옮겼습니다. ADR 0015 Implementation 표의
`record_answer_fixtures.py` 는 구현 예정이라, 다시 녹화하려면 지금은
`scripts/grounding_experiments.py` 를 돌리고 옮겨야 합니다.

## 프롬프트를 바꾸면

`prompt/answer-generate.json` 의 `version` 을 올리고 다시 녹화합니다.
`system` 이 프롬프트 본문에 들어가므로 문구를 고치면 컨텍스트가 아니라 요청이 달라집니다.
지문은 질문과 컨텍스트만으로 계산하므로 키는 그대로이고, 응답만 다시 녹화하면 됩니다.
