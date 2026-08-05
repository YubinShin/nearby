import { useState } from "react";

import styles from "@/features/compare/RrfMerge.module.css";
import { scoreOf } from "@/shared/lib/format";
import ui from "@/shared/styles/ui.module.css";
import type { PlaceHit } from "@/shared/types";

const ROW_H = 26;
const LIMIT = 10;
const LANE_W = 28;
const CENTER_X = 36;
const RIGHT_X = 72;

interface Props {
  keyword: PlaceHit[];
  vector: PlaceHit[];
  hybrid: PlaceHit[];
}

interface Link {
  key: string;
  placeId: string;
  lane: "keyword" | "vector";
  x1: string;
  y1: number;
  x2: string;
  y2: number;
}

const rowY = (index: number) => index * ROW_H + ROW_H / 2;

export function RrfMerge({ keyword, vector, hybrid }: Props) {
  const [hovered, setHovered] = useState<string | null>(null);

  const kw = keyword.slice(0, LIMIT);
  const vec = vector.slice(0, LIMIT);
  const hyb = hybrid.slice(0, LIMIT);

  const kwIndex = new Map(kw.map((hit, i) => [hit.placeId, i]));
  const vecIndex = new Map(vec.map((hit, i) => [hit.placeId, i]));

  const links: Link[] = [];
  hyb.forEach((hit, i) => {
    const fromKw = kwIndex.get(hit.placeId);
    if (fromKw != null) {
      links.push({
        key: `kw-${hit.placeId}`,
        placeId: hit.placeId,
        lane: "keyword",
        x1: `${LANE_W}%`,
        y1: rowY(fromKw),
        x2: `${CENTER_X}%`,
        y2: rowY(i),
      });
    }
    const fromVec = vecIndex.get(hit.placeId);
    if (fromVec != null) {
      links.push({
        key: `vec-${hit.placeId}`,
        placeId: hit.placeId,
        lane: "vector",
        x1: `${CENTER_X + LANE_W}%`,
        y1: rowY(i),
        x2: `${RIGHT_X}%`,
        y2: rowY(fromVec),
      });
    }
  });

  const height = Math.max(kw.length, vec.length, hyb.length, 1) * ROW_H;

  return (
    <section className={ui.panel} style={{ marginBottom: 14 }}>
      <h2 className={ui.head}>
        RRF 결합
        <span className={ui.sub}>두 순위 목록이 하나로 합쳐지는 과정 · 상위 {LIMIT}건</span>
        <span className={ui.meta}>선 = 같은 가게</span>
      </h2>

      <div className={styles.heads}>
        <span className={`${styles.head} ${styles.headKw}`} style={{ width: `${LANE_W}%` }}>
          키워드 {keyword.length}건
        </span>
        <span className={styles.head} style={{ width: `${CENTER_X - LANE_W}%` }} />
        <span className={`${styles.head} ${styles.headHyb}`} style={{ width: `${LANE_W}%` }}>
          RRF 최종
        </span>
        <span className={styles.head} style={{ width: `${RIGHT_X - CENTER_X - LANE_W}%` }} />
        <span className={`${styles.head} ${styles.headVec}`} style={{ width: `${LANE_W}%` }}>
          벡터 {vector.length}건
        </span>
      </div>

      <div className={styles.merge} style={{ height }}>
        <svg className={styles.links}>
          {links.map((link) => (
            <line
              key={link.key}
              className={`${styles.link} ${link.lane === "keyword" ? styles.linkKw : styles.linkVec} ${
                hovered === link.placeId ? styles.on : ""
              }`}
              x1={link.x1}
              y1={link.y1}
              x2={link.x2}
              y2={link.y2}
            />
          ))}
        </svg>

        <Lane
          hits={kw}
          channel="keyword"
          left={0}
          hovered={hovered}
          onHover={setHovered}
          emptyNote="0건 — 이 레인은 비었습니다"
        />
        <Lane
          hits={hyb}
          channel="hybrid"
          left={CENTER_X}
          hovered={hovered}
          onHover={setHovered}
          emptyNote="0건"
          center
        />
        <Lane
          hits={vec}
          channel="vector"
          left={RIGHT_X}
          hovered={hovered}
          onHover={setHovered}
          emptyNote="0건 — 이 레인은 비었습니다"
        />
      </div>
    </section>
  );
}

function Lane({
  hits,
  channel,
  left,
  hovered,
  onHover,
  emptyNote,
  center = false,
}: {
  hits: PlaceHit[];
  channel: "keyword" | "vector" | "hybrid";
  left: number;
  hovered: string | null;
  onHover: (placeId: string | null) => void;
  emptyNote: string;
  center?: boolean;
}) {
  if (hits.length === 0) {
    return (
      <ol className={`${styles.lane}`} style={{ left: `${left}%`, width: `${LANE_W}%` }}>
        <li className={styles.empty}>{emptyNote}</li>
      </ol>
    );
  }

  return (
    <ol
      className={`${styles.lane} ${center ? styles.center : ""}`}
      style={{ left: `${left}%`, width: `${LANE_W}%` }}
      onMouseLeave={() => onHover(null)}
    >
      {hits.map((hit, i) => (
        <li
          key={hit.placeId}
          className={`${styles.row} ${hovered === hit.placeId ? styles.on : ""}`}
          style={{ height: ROW_H }}
          onMouseEnter={() => onHover(hit.placeId)}
        >
          <span className={styles.rank}>{i + 1}</span>
          <span className={styles.name}>{hit.name}</span>
          {center ? (
            <span className={styles.tags}>
              <LaneTag lane="keyword" rank={hit.ranks?.keyword} />
              <LaneTag lane="vector" rank={hit.ranks?.vector} />
            </span>
          ) : (
            <span className={styles.score}>{scoreOf(channel, hit.score)}</span>
          )}
        </li>
      ))}
    </ol>
  );
}

function LaneTag({ lane, rank }: { lane: "keyword" | "vector"; rank: number | undefined }) {
  if (rank == null) return null;
  const off = rank > LIMIT;
  return (
    <span
      className={`${styles.tiny} ${lane === "keyword" ? styles.tinyKw : styles.tinyVec} ${
        off ? styles.tinyOff : ""
      }`}
      title={off ? `${lane} 레인 ${rank}위 — 상위 ${LIMIT} 밖` : `${lane} 레인 ${rank}위`}
    >
      {lane === "keyword" ? "KW" : "VEC"} #{rank}
    </span>
  );
}
