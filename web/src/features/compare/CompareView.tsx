import { type FormEvent, useState } from "react";

import styles from "@/features/compare/CompareView.module.css";
import { RrfMerge } from "@/features/compare/RrfMerge";
import { ApiError, searchApi, type Timed } from "@/shared/api/client";
import { HitList } from "@/shared/components/HitList";
import { applyGeo, GEO_PRESETS } from "@/shared/lib/format";
import ui from "@/shared/styles/ui.module.css";
import type { HybridResponse, PlaceHit, SearchResponse } from "@/shared/types";

const CHANNELS = [
  { id: "keyword", label: "키워드", sub: "글자", dot: styles.dotKeyword, empty: "글자가 겹치는 문서가 없습니다." },
  { id: "vector", label: "벡터", sub: "뜻", dot: styles.dotVector, empty: "문턱(0.84)을 넘은 결과가 없습니다." },
  { id: "hybrid", label: "하이브리드", sub: "RRF 결합", dot: styles.dotHybrid, empty: "두 채널 모두 후보가 없습니다." },
] as const;

type ChannelId = (typeof CHANNELS)[number]["id"];

const EXAMPLES = [
  { q: "회 먹을 데", why: "키워드 0건" },
  { q: "머리 자르는 곳", why: "키워드 0건" },
  { q: "혼밥하기 좋은 집", why: "키워드 0건" },
  { q: "스타벅스", why: "이름에서 브랜드 탈락" },
  { q: "차 고치는 곳", why: "벡터가 낚임" },
  { q: "ㅁㄴㅇㄹ", why: "엉터리" },
  { q: "역삼동 카페", why: "둘 다 잘함" },
];

type ChannelState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ok"; result: Timed<SearchResponse | HybridResponse> }
  | { status: "error"; message: string };

const IDLE: Record<ChannelId, ChannelState> = {
  keyword: { status: "idle" },
  vector: { status: "idle" },
  hybrid: { status: "idle" },
};

interface Props {
  query: string;
  onQueryChange: (query: string) => void;
}

export function CompareView({ query, onQueryChange }: Props) {
  const [size, setSize] = useState("10");
  const [sigungu, setSigungu] = useState("");
  const [dong, setDong] = useState("");
  const [category, setCategory] = useState("");
  const [geo, setGeo] = useState("");
  const [radius, setRadius] = useState("500");
  const [state, setState] = useState(IDLE);
  const [running, setRunning] = useState(false);

  async function run(raw: string) {
    const q = raw.trim();
    if (!q) return;
    setRunning(true);
    setState({ keyword: { status: "loading" }, vector: { status: "loading" }, hybrid: { status: "loading" } });

    const params = new URLSearchParams({ q, size: size || "10" });
    if (sigungu.trim()) params.set("sigungu", sigungu.trim());
    if (dong.trim()) params.set("dong", dong.trim());
    if (category.trim()) params.set("category", category.trim());
    applyGeo(params, geo, radius);

    const settled = await Promise.allSettled([
      searchApi.keyword(params),
      searchApi.vector(params),
      searchApi.hybrid(params),
    ]);

    const next = { ...IDLE };
    CHANNELS.forEach((channel, i) => {
      const outcome = settled[i];
      next[channel.id] =
        outcome.status === "fulfilled"
          ? { status: "ok", result: outcome.value }
          : { status: "error", message: messageOf(outcome.reason) };
    });
    setState(next);
    setRunning(false);
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
            placeholder="예: 회 먹을 데"
            autoComplete="off"
          />
          <label className={ui.field}>
            size
            <input type="number" min={1} max={50} value={size} onChange={(e) => setSize(e.target.value)} />
          </label>
          <button className={ui.go} type="submit" disabled={running}>
            검색
          </button>
        </div>
        <div className={ui.row}>
          <label className={ui.field}>
            시군구
            <input value={sigungu} onChange={(e) => setSigungu(e.target.value)} placeholder="강남구" />
          </label>
          <label className={ui.field}>
            행정동
            <input value={dong} onChange={(e) => setDong(e.target.value)} placeholder="역삼1동" />
          </label>
          <label className={ui.field}>
            카테고리
            <input value={category} onChange={(e) => setCategory(e.target.value)} placeholder="음식" />
          </label>
          <label className={ui.field}>
            위치
            <select value={geo} onChange={(e) => setGeo(e.target.value)}>
              {GEO_PRESETS.map((preset) => (
                <option key={preset.label} value={preset.value}>
                  {preset.label}
                </option>
              ))}
            </select>
          </label>
          <label className={ui.field}>
            반경(m)
            <input type="number" min={1} max={50000} value={radius} onChange={(e) => setRadius(e.target.value)} />
          </label>
        </div>
        <div className={`${ui.row} ${ui.chips}`}>
          <span className={ui.chipsLabel}>실측에서 나온 예시</span>
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
      </form>

      {state.hybrid.status === "ok" && (
        <RrfMerge
          keyword={hitsOf(state.keyword)}
          vector={hitsOf(state.vector)}
          hybrid={hitsOf(state.hybrid)}
        />
      )}

      <div className={styles.cols}>
        {CHANNELS.map((channel) => (
          <section key={channel.id} className={`${ui.panel} ${styles.col}`}>
            <h2 className={`${ui.head} ${styles.colHead}`}>
              <span className={`${styles.dot} ${channel.dot}`} />
              {channel.label}
              <span className={ui.sub}>{channel.sub}</span>
              <span className={ui.meta}>{metaOf(state[channel.id])}</span>
            </h2>
            <div className={styles.colBody}>
              <ChannelBody channel={channel.id} state={state[channel.id]} empty={channel.empty} />
            </div>
          </section>
        ))}
      </div>
    </>
  );
}

function ChannelBody({
  channel,
  state,
  empty,
}: {
  channel: ChannelId;
  state: ChannelState;
  empty: string;
}) {
  if (state.status === "idle") return <p className={ui.note}>검색어를 입력하십시오.</p>;
  if (state.status === "loading") return <p className={ui.note}>조회 중…</p>;
  if (state.status === "error") {
    return <p className={`${ui.note} ${ui.noteBad}`}>호출 실패 — {state.message}</p>;
  }

  const body = state.result.body;
  const dead = (body as HybridResponse).channels?.filter((c) => c.failed).map((c) => c.name) ?? [];
  return (
    <>
      {dead.length > 0 && (
        <p className={`${ui.note} ${ui.noteBad}`}>{dead.join(", ")} 채널이 실패해 나머지로만 응답했습니다.</p>
      )}
      <HitList channel={channel} hits={body.hits} emptyNote={empty} />
    </>
  );
}

function hitsOf(state: ChannelState): PlaceHit[] {
  return state.status === "ok" ? state.result.body.hits : [];
}

function metaOf(state: ChannelState): string {
  if (state.status === "idle") return "–";
  if (state.status === "loading") return "…";
  if (state.status === "error") return "실패";
  const { body, rtt } = state.result;
  const bits = [`total ${body.total}`, `${body.tookMs}ms`];
  if (body.relaxed) bits.push("완화 재질의");
  if ((body as HybridResponse).degraded) bits.push("degraded");
  return `${bits.join(" · ")} (왕복 ${rtt}ms)`;
}

function messageOf(reason: unknown): string {
  if (reason instanceof ApiError) {
    return reason.status > 0 ? `HTTP ${reason.status} — ${reason.message}` : reason.message;
  }
  return reason instanceof Error ? reason.message : String(reason);
}