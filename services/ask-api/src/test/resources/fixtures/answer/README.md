# fixtures/answer

`FixtureLlmClient.answer`가 사용하는 답변 생성 응답 원문입니다. CI에서는 실제 LLM API를 호출하지 않고 이 디렉터리의 fixture를 사용합니다.

## Files

| File | Description |
| --- | --- |
| `index.json` | 지문과 응답 파일의 매핑 정보, 항목별 `question`, `source`, `context`를 저장합니다. |
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

녹화본은 컨텍스트를 어디서 얻었는지에 따라 두 종류이며, 항목별 `context` 필드가 이를 구분합니다. 전부 `source: recorded` · `model: gemini-3.5-flash`입니다.

### context: pipeline

실제 `ask-api`를 호출해 받은 검색 결과로 컨텍스트를 만들어 녹화했습니다. fixture 모드가 실제 질의에 답변하는 경로입니다. (Recorded: **2026-08-08**)

질문은 `fixtures/index.json`의 질의 목록과 같습니다. 질의 이해 픽스처가 있어야 파싱이 결정적이고, 그래야 검색어와 검색 결과가 고정되어 키가 재현됩니다.

### context: trap

`scripts/grounding_experiments.py`의 트랩 시나리오입니다. 컨텍스트가 스크립트 상수라 실제 검색 결과로는 이 키에 도달하지 못합니다. 근거 검증 단위 테스트가 사용합니다. (Recorded: **2026-08-04**)

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

`scripts/record_answer_fixtures.py`가 두 종류를 모두 녹화합니다. `--source`로 어느 쪽인지 지정합니다.

```bash
# 파이프라인 — ask-api 가 psp.ask.llm=fixture 로 떠 있어야 함
export GEMINI_API_KEY=...
python3 scripts/record_answer_fixtures.py --source pipeline

# 녹화한 키에 실제로 도달하는지 확인
python3 scripts/record_answer_fixtures.py --verify

# 트랩
python3 scripts/record_answer_fixtures.py --source trap
```

프롬프트를 변경했다면 `prompt/answer-generate.json`의 `version`을 올린 뒤 다시 녹화합니다. `system` 프롬프트가 요청 본문에 포함되므로 문구를 수정하면 LLM 요청 자체가 달라지지만, fixture의 키는 **질문과 렌더된 컨텍스트만으로 계산**하므로 그대로입니다. 기존 파일을 덮어써 응답만 새로 녹화하면 됩니다.

pipeline 녹화본은 색인 내용에 묶입니다. 재색인이나 다른 코퍼스로 교체하면 검색 결과가 달라져 키가 어긋납니다. `--verify`가 이를 감지합니다.
