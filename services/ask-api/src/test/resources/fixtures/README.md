# fixtures

`FixtureLlmClient`가 사용하는 LLM 응답 원문입니다. CI에서는 실제 API를 호출하지 않고 이 디렉터리의 파일을 읽어 테스트를 수행합니다.

## Files

| File | Description |
| --- | --- |
| `index.json` | 질의와 응답 파일의 매핑 정보, 전체 `promptVersion`, 항목별 `model`, 항목별 `promptVersion`을 저장합니다. |
| `<sha256 앞 12자>.json` | Gemini `generateContent` API의 원본 응답입니다. |

응답 파일명은 `sha256(NFC(질의))`의 앞 12자를 사용합니다. 한국어 파일명이 macOS에서 NFD 형태로 저장되는 문제를 피하기 위해 해시 기반 이름을 사용합니다.

## Contents

현재 **27개**이며 전부 `source: recorded` · `model: gemini-3.5-flash`입니다. (Recorded: **2026-08-04**)

- 골든셋 25건
- 골든셋에는 없지만 단위 테스트에서 사용하는 2건
    - `역삼동 조용히 공부할 곳`
    - `강남역 500m 안에 편의점`

## Re-recording

`record_llm_fixtures.py`는 현재 설정과 인덱스 정보를 비교해 `model` 또는 `promptVersion`이 변경된 항목만 다시 녹화합니다. 골든셋에 없는 질의라도 `index.json`에 있으면 대상에 포함합니다.

프롬프트를 변경했다면 `prompt/ask-parse.json`의 `version`을 올린 뒤 스크립트를 실행합니다. 항목별로 변경 여부를 판단하므로 `--force`는 필요하지 않고, 일부 항목이 실패해도 다음 실행에서 실패한 항목만 다시 호출합니다.

## Versioning

`index.json`의 전체 `promptVersion`은 **모든 항목이 새 버전으로 재녹화된 이후에만** 갱신됩니다. 버전이 일치하지 않으면 `FixtureLlmClient`는 기동 시 경고 로그를 출력하고 어긋난 질의를 함께 나열합니다.
