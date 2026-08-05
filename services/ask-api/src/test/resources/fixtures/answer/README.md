# fixtures/answer

`FixtureLlmClient.answer`가 사용하는 답변 생성 응답 원문입니다. CI에서는 실제 LLM API를 호출하지 않고 이 디렉터리의 fixture를 사용합니다.

## Files

| File | Description |
| --- | --- |
| `index.json` | 지문과 응답 파일의 매핑 정보, 항목별 `experiment`, `question`, `source`를 저장합니다. |
| `<sha256 앞 12자>.json` | Gemini `generateContent` API의 원본 응답입니다. |

응답 파일명은 다음 키의 SHA-256 해시 앞 12자를 사용합니다.

```text
sha256(
    NFC(질문)
    + "\n---\n"
    + NFC(렌더된 컨텍스트)
)
```

질문만으로는 키를 구분하지 못합니다. `회 먹을 데 있어?` 하나가 컨텍스트에 따라 `baseline` · `empty_context` · `garbage_input` 세 항목으로 갈립니다.

키에 렌더된 컨텍스트가 포함되므로 `AnswerContext.render()`의 출력이 녹화 당시와 한 글자라도 달라지면 fixture 조회에 실패합니다. 렌더러와 녹화본의 일치는 `AskAnswerMappingTest`에서 검증합니다.

## Contents

현재 **8개**이며 전부 `source: recorded` · `model: gemini-3.5-flash` 입니다. (Recorded: **2026-08-04**)

모두 `scripts/grounding_experiments.py`의 함정(trap) 시나리오에서 생성했고, 원본 응답은 `scripts/fixtures/260804/`에 있습니다.

| Experiment | Question | Context |
| --- | --- | --- |
| `baseline` | 회 먹을 데 있어? | 횟집 5건 |
| `implicit_condition` | 맛있고 가까운 회 먹을 데 있어? | 횟집 5건 |
| `empty_context` | 회 먹을 데 있어? | 결과 0건 |
| `false_premise` | 어방참치 초밥 맛있기로 유명하죠? | 횟집 5건 |
| `knowledge_leak_famous` | 스타벅스 어때? | 스타벅스 5건 |
| `knowledge_leak_unknown` | 어방참치 어때? | 횟집 5건 |
| `context_mismatch` | 어방참치 어때? | 스타벅스 5건 |
| `garbage_input` | 회 먹을 데 있어? | 횟집 5건 + 회계사무소 1건 |

## Re-recording

자동 재녹화 스크립트(`record_answer_fixtures.py`)는 ADR 0015 Implementation 표에 정의되어 있으나 아직 구현되지 않았습니다. 현재 절차는 두 단계입니다.

1. `scripts/grounding_experiments.py`를 실행합니다.
2. 생성된 응답을 `scripts/fixtures/<날짜>/`에서 `fixtures/answer/`로 복사합니다.

프롬프트를 변경했다면 `prompt/answer-generate.json`의 `version`을 올린 뒤 다시 녹화합니다. `system` 프롬프트가 요청 본문에 포함되므로 문구를 수정하면 LLM 요청 자체가 달라지지만, fixture의 키는 **질문과 렌더된 컨텍스트만으로 계산**하므로 그대로입니다. 기존 파일을 덮어써 응답만 새로 녹화하면 됩니다.
