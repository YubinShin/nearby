import { type FormEvent, useState } from "react";

import styles from "@/features/ask/AskView.module.css";
import { ApiError, askApi, type Timed } from "@/shared/api/client";
import { HitList } from "@/shared/components/HitList";
import { applyGeo, GEO_PRESETS } from "@/shared/lib/format";
import ui from "@/shared/styles/ui.module.css";
import type { AskResponse, ParsedQuery, SearchRequestPlan } from "@/shared/types";

const EXAMPLES = [
  { q: "역삼동 조용히 공부할 곳", why: "지명 + 업종 추론" },
  { q: "강남역 500m 안에 편의점", why: "radius_m 추출" },
  { q: "평점 4.5 이상 카페", why: "코퍼스에 없는 속성" },
  { q: "1만원 이하 파스타", why: "가격 속성" },
  { q: "지금 문 연 약국", why: "영업시간 속성" },
  { q: "제주도 흑돼지 맛집", why: "코퍼스 밖 지역" },
  { q: "회 먹을 데", why: "키워드 0건이던 질의" },
];

type State =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ok"; result: Timed<AskResponse> }
  | { status: "error"; message: string; upstream: string | null };

interface Props {
  query: string;
  onQueryChange: (query: string) => void;
  onSendToCompare: (query: string) => void;
}

export function AskView({ query, onQueryChange, onSendToCompare }: Props) {
  const [size, setSize] = useState("10");
  const [geo, setGeo] = useState("");
  const [state, setState] = useState<State>({ status: "idle" });

  async function run(raw: string) {
    const q = raw.trim();
    if (!q) return;
    setState({ status: "loading" });

    const params = new URLSearchParams({ q, size: size || "10" });
    applyGeo(params, geo, "");

    try {
      setState({ status: "ok", result: await askApi.ask(params) });
    } catch (error) {
      const api = error instanceof ApiError ? error : null;
      setState({
        status: "error",
        message: api ? `HTTP ${api.status} — ${api.message}` : String(error),
        upstream: api?.upstream ?? null,
      });
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void run(query);
  }

  return (
    <>
      <form className={`${ui.panel} ${styles.form}`} onSubmit={submit}>
        <div className={ui.row}>
          <input
            className={ui.q}
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
            placeholder="예: 역삼동 조용히 공부할 곳"
            autoComplete="off"
          />
          <label className={ui.field}>
            size
            <input type="number" min={1} max={50} value={size} onChange={(e) => setSize(e.target.value)} />
          </label>
          <label className={ui.field}>
            기준 좌표
            <select value={geo} onChange={(e) => setGeo(e.target.value)}>
              {GEO_PRESETS.map((preset) => (
                <option key={preset.label} value={preset.value}>
                  {preset.label}
                </option>
              ))}
            </select>
          </label>
          <button className={ui.go} type="submit" disabled={state.status === "loading"}>
            질의
          </button>
        </div>
        <div className={`${ui.row} ${ui.chips}`}>
          <span className={ui.chipsLabel}>픽스처에 녹화된 질의</span>
          {EXAMPLES.map((example) => (
            <button
              key={example.q}
              type="button"
              className={ui.chip}
              onClick={() => {
                onQueryChange(example.q);
                void run(example.q);
              }}
            >
              {example.q}
              <small>{example.why}</small>
            </button>
          ))}
        </div>
        <p className={`${ui.note} ${ui.dim}`}>
          반경은 입력하지 않습니다. LLM 이 뽑은 <code>radius_m</code> 을 쓰고, 기준 좌표가 있을 때만 적용합니다.
        </p>
      </form>

      {state.status === "loading" && <p className={`${ui.panel} ${ui.note}`}>LLM 왕복 중… (2~3초)</p>}

      {state.status === "error" && (
        <p className={`${ui.panel} ${ui.note} ${ui.noteBad}`}>
          {state.upstream ? `${state.upstream} 상류 실패 — ` : "ask-api 호출 실패 — "}
          {state.message}
        </p>
      )}

      {state.status === "ok" && (
        <Result result={state.result} onSendToCompare={onSendToCompare} />
      )}
    </>
  );
}

function Result({
  result,
  onSendToCompare,
}: {
  result: Timed<AskResponse>;
  onSendToCompare: (query: string) => void;
}) {
  const { body, rtt } = result;
  const llmShare = body.tookMs > 0 ? (body.llmTookMs / body.tookMs) * 100 : 0;
  const searchShare = body.tookMs > 0 ? (body.searchTookMs / body.tookMs) * 100 : 0;
  const replayed = body.llmVendor === "fixture";

  return (
    <>
      <div className={`${ui.panel} ${styles.status}`}>
        {body.degraded ? (
          body.degradedBy.map((stage) => (
            <span key={stage} className={`${ui.tag} ${ui.tagBad}`}>
              degraded · {stage === "llm" ? "LLM 실패, 원문 질의로 검색" : "검색 반쪽 응답"}
            </span>
          ))
        ) : (
          <span className={`${ui.tag} ${ui.tagOk}`}>정상</span>
        )}
        {body.llmVendor && (
          <span className={`${ui.tag} ${replayed ? ui.tagWarn : ""}`}>
            {replayed ? "fixture · 녹화 재생" : `LLM ${body.llmVendor}`}
          </span>
        )}
        <div className={styles.bar}>
          <div className={styles.barLlm} style={{ width: `${llmShare}%` }} />
          <div className={styles.barSearch} style={{ width: `${searchShare}%` }} />
        </div>
        <div className={styles.legend}>
          <span>
            <i className={`${styles.swatch} ${styles.barLlm}`} />
            LLM <b>{body.llmTookMs}ms</b>
          </span>
          <span>
            <i className={`${styles.swatch} ${styles.barSearch}`} />
            검색 <b>{body.searchTookMs}ms</b>
          </span>
          <span>
            합계 <b>{body.tookMs}ms</b> · 왕복 {rtt}ms
          </span>
        </div>
      </div>

      {replayed && (
        <p className={`${ui.panel} ${ui.note} ${ui.noteWarn}`} style={{ marginBottom: 14 }}>
          이 LLM 구간은 실제 호출이 아니라 디스크에 녹화된 응답을 읽은 시간입니다. 실호출 왕복은
          2.0~3.0초로 검색(하이브리드 중앙값 9.7ms)을 200배 넘게 압도합니다 (ADR 0014). 실측을 보려면
          <code> GEMINI_API_KEY </code>를 넣고 <code>psp.ask.llm=gemini</code> 로 기동하십시오.
        </p>
      )}

      <div className={styles.split}>
        <section className={ui.panel}>
          <h2 className={ui.head}>
            LLM 이 이해한 것
            <span className={ui.sub}>parsed</span>
          </h2>
          <div className={ui.body}>
            <ParsedTable parsed={body.parsed} applied={body.applied} />
          </div>
        </section>

        <section className={ui.panel}>
          <h2 className={ui.head}>
            실제로 보낸 요청
            <span className={ui.sub}>applied → /v1/hsearch</span>
          </h2>
          <div className={ui.body}>
            <AppliedTable applied={body.applied} />
            <div className={styles.rewrite}>
              <span className={ui.dim}>재작성된 질의</span>
              <code className={styles.rewriteQ}>{body.applied.q}</code>
              <button type="button" className={ui.ghost} onClick={() => onSendToCompare(body.applied.q)}>
                채널 비교로 보내기
              </button>
            </div>
          </div>
        </section>
      </div>

      <section className={ui.panel}>
        <h2 className={ui.head}>
          검색 결과
          <span className={ui.sub}>ask 응답의 search 필드 · 하이브리드</span>
          <span className={ui.meta}>
            total {body.search.total} · {body.search.tookMs}ms
          </span>
        </h2>
        <div className={styles.hits}>
          <HitList
            channel="hybrid"
            hits={body.search.hits ?? []}
            emptyNote={
              body.parsed?.expectsEmpty
                ? "LLM 이 expects_empty 로 표시한 질의입니다."
                : "두 채널 모두 후보가 없습니다."
            }
          />
        </div>
      </section>
    </>
  );
}

function ParsedTable({ parsed, applied }: { parsed: ParsedQuery | null; applied: SearchRequestPlan }) {
  if (!parsed) {
    return (
      <p className={`${ui.note} ${ui.noteBad}`}>
        LLM 이 응답하지 못했습니다. 원문 질의를 그대로 검색했습니다.
      </p>
    );
  }

  const rows: Array<{ key: string; value: string; note: string }> = [
    { key: "keyword", value: parsed.keyword, note: "재작성 질의에 포함" },
    {
      key: "category_hint",
      value: parsed.categoryHint ?? "–",
      note: parsed.categoryHint ? "질의 문자열에 합침 · 전용 파라미터 없음" : "–",
    },
    {
      key: "geo_anchor",
      value: parsed.geoAnchor ?? "–",
      note: parsed.geoAnchor ? "질의 문자열에 합침 · dong/sigungu 로는 못 옮김" : "–",
    },
    {
      key: "radius_m",
      value: parsed.radiusM == null ? "–" : `${parsed.radiusM}m`,
      note:
        parsed.radiusM == null
          ? "–"
          : applied.radius != null
            ? `radius=${applied.radius} 로 적용`
            : "기준 좌표가 없어 미적용",
    },
    {
      key: "expects_empty",
      value: String(parsed.expectsEmpty),
      note: "응답에만 실림 · 검색에는 영향 없음",
    },
  ];

  return (
    <dl className={ui.kv}>
      {rows.map((row) => (
        <FieldRow key={row.key} label={row.key} value={row.value} note={row.note} />
      ))}
    </dl>
  );
}

function AppliedTable({ applied }: { applied: SearchRequestPlan }) {
  return (
    <dl className={ui.kv}>
      <FieldRow label="q" value={applied.q} />
      <FieldRow label="size" value={String(applied.size)} />
      <FieldRow
        label="lat, lon"
        value={applied.lat == null || applied.lon == null ? "–" : `${applied.lat}, ${applied.lon}`}
      />
      <FieldRow label="radius" value={applied.radius == null ? "–" : `${applied.radius}m`} />
      <dt>unmapped</dt>
      <dd>
        {applied.unmapped.length === 0 ? (
          <span className={ui.dim}>없음</span>
        ) : (
          <div className={styles.flags}>
            {applied.unmapped.map((name) => (
              <span key={name} className={`${ui.tag} ${ui.tagWarn}`}>
                {name}
              </span>
            ))}
          </div>
        )}
      </dd>
      <dt>unsupported</dt>
      <dd>
        {applied.unsupported.length === 0 ? (
          <span className={ui.dim}>없음</span>
        ) : (
          <>
            <div className={styles.flags}>
              {applied.unsupported.map((name) => (
                <span key={name} className={`${ui.tag} ${ui.tagBad}`}>
                  {name}
                </span>
              ))}
            </div>
            <p className={`${ui.note} ${ui.dim}`} style={{ padding: "6px 0 0" }}>
              코퍼스에 데이터가 없는 속성입니다. 결과를 좁히지 않고 이름만 알립니다.
            </p>
          </>
        )}
      </dd>
    </dl>
  );
}

function FieldRow({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>
        {value}
        {note && note !== "–" && <span className={ui.dim}> — {note}</span>}
      </dd>
    </>
  );
}