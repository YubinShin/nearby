export interface AnswerSentence {
  text: string;
  evidence: string[];
}

export interface GroundingAnswer {
  found: boolean;
  unverifiable_conditions: string[];
  sentences: AnswerSentence[];
}

export interface GeminiUsage {
  promptTokenCount?: number;
  candidatesTokenCount?: number;
  totalTokenCount?: number;
  thoughtsTokenCount?: number;
}

interface GeminiRaw {
  candidates?: Array<{
    finishReason?: string;
    content?: { parts?: Array<{ text?: string }> };
  }>;
  usageMetadata?: GeminiUsage;
  modelVersion?: string;
}

interface FixtureFile {
  recorded_at: string;
  model_version: string;
  thinking_level: string;
  experiment: string;
  question: string;
  context: string;
  note?: string;
  checks?: Record<string, unknown>;
  raw_response: GeminiRaw;
}

interface ScoreboardEntry {
  experiment: string;
  verdict: string;
  findings: string[];
}

interface ScoreboardFile {
  run_at: string;
  model: string;
  thinking_level: string;
  results: ScoreboardEntry[];
}

export interface ContextPlace {
  id: string;
  name: string;
  category: string;
  dong: string;
  address: string;
}

export interface GroundingExperiment {
  id: string;
  note: string | null;
  question: string;
  context: string;
  places: ContextPlace[];
  answer: GroundingAnswer | null;
  finishReason: string | null;
  usage: GeminiUsage | null;
  modelVersion: string;
  recordedAt: string;
  verdict: string;
  findings: string[];
}

export interface GroundingRun {
  id: string;
  runAt: string | null;
  model: string | null;
  thinkingLevel: string | null;
  experiments: GroundingExperiment[];
}

const FALLBACK_NOTES: Record<string, string> = {
  baseline: "정상 질의 + 정상 컨텍스트 — 그라운딩 기본기",
  implicit_condition: "silent condition dropping — '맛있고 가까운'을 떨구는가",
  empty_context: "'없다' 테스트 — 빈손에서 지어내지 않는가",
  false_premise: "거짓 전제 — '맛있기로 유명하죠?'를 삼키는가",
  knowledge_leak_famous: "지식 누출(유명 브랜드) — 스타벅스 배경지식이 새는가",
  knowledge_leak_unknown: "지식 누출 대조군(무명) — '어때'를 존재 확인으로 읽는지",
  context_mismatch: "컨텍스트-질문 불일치 — 스타벅스 문서로 어방참치를 물으면",
  garbage_input: "쓰레기 입력 — 회계사무소를 걸러내는가",
};

const CONTEXT_LINE = /^-\s*\[([^\]]+)\]\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+)$/;

const modules = import.meta.glob<unknown>("../../../../scripts/fixtures/*/*.json", {
  eager: true,
  import: "default",
});

function parsePlaces(context: string): ContextPlace[] {
  return context
    .split("\n")
    .map((line) => CONTEXT_LINE.exec(line.trim()))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => ({
      id: match[1],
      name: match[2],
      category: match[3],
      dong: match[4],
      address: match[5],
    }));
}

function parseAnswer(raw: GeminiRaw): GroundingAnswer | null {
  const text = raw.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) return null;
  try {
    const parsed = JSON.parse(text) as Partial<GroundingAnswer>;
    return {
      found: parsed.found ?? false,
      unverifiable_conditions: parsed.unverifiable_conditions ?? [],
      sentences: parsed.sentences ?? [],
    };
  } catch {
    return null;
  }
}

function toExperiment(file: FixtureFile, entry: ScoreboardEntry | undefined): GroundingExperiment {
  return {
    id: file.experiment,
    note: file.note ?? FALLBACK_NOTES[file.experiment] ?? null,
    question: file.question,
    context: file.context,
    places: parsePlaces(file.context),
    answer: parseAnswer(file.raw_response),
    finishReason: file.raw_response.candidates?.[0]?.finishReason ?? null,
    usage: file.raw_response.usageMetadata ?? null,
    modelVersion: file.raw_response.modelVersion ?? file.model_version,
    recordedAt: file.recorded_at,
    verdict: entry?.verdict ?? "UNKNOWN",
    findings: entry?.findings ?? [],
  };
}

function loadRuns(): GroundingRun[] {
  const scoreboards = new Map<string, ScoreboardFile>();
  const files = new Map<string, FixtureFile[]>();

  for (const [path, loaded] of Object.entries(modules)) {
    const segments = path.split("/");
    const name = segments[segments.length - 1];
    const runId = segments[segments.length - 2];

    if (name === "_scoreboard.json") {
      scoreboards.set(runId, loaded as ScoreboardFile);
      continue;
    }
    const bucket = files.get(runId) ?? [];
    bucket.push(loaded as FixtureFile);
    files.set(runId, bucket);
  }

  return [...files.keys()]
    .sort((a, b) => b.localeCompare(a))
    .map((runId) => {
      const scoreboard = scoreboards.get(runId) ?? null;
      const byId = new Map(scoreboard?.results.map((result) => [result.experiment, result]) ?? []);
      const bucket = files.get(runId) ?? [];
      const order = scoreboard?.results.map((result) => result.experiment) ?? [];

      const experiments = bucket
        .map((file) => toExperiment(file, byId.get(file.experiment)))
        .sort((a, b) => {
          const ai = order.indexOf(a.id);
          const bi = order.indexOf(b.id);
          if (ai === -1 && bi === -1) return a.id.localeCompare(b.id);
          if (ai === -1) return 1;
          if (bi === -1) return -1;
          return ai - bi;
        });

      return {
        id: runId,
        runAt: scoreboard?.run_at ?? null,
        model: scoreboard?.model ?? null,
        thinkingLevel: scoreboard?.thinking_level ?? null,
        experiments,
      };
    });
}

export const RUNS = loadRuns();