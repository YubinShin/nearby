import styles from "@/shared/components/HitList.module.css";
import { scoreOf } from "@/shared/lib/format";
import ui from "@/shared/styles/ui.module.css";
import type { PlaceHit } from "@/shared/types";

interface Props {
  channel: "keyword" | "vector" | "hybrid";
  hits: PlaceHit[];
  emptyNote: string;
}

function Badges({ channel, hit }: { channel: Props["channel"]; hit: PlaceHit }) {
  if (channel !== "hybrid") {
    return <span className={ui.tag}>score {scoreOf(channel, hit.score)}</span>;
  }
  const ranks = hit.ranks ?? {};
  const scores = hit.scores ?? {};
  const agreed = Object.keys(ranks).length === 2;
  return (
    <>
      {ranks.keyword != null && (
        <span className={`${ui.tag} ${agreed ? ui.tagHyb : ui.tagKw}`}>
          KW #{ranks.keyword} · {scoreOf("keyword", scores.keyword)}
        </span>
      )}
      {ranks.vector != null && (
        <span className={`${ui.tag} ${agreed ? ui.tagHyb : ui.tagVec}`}>
          VEC #{ranks.vector} · {scoreOf("vector", scores.vector)}
        </span>
      )}
      {agreed && <span className={`${ui.tag} ${ui.tagHyb}`}>두 채널 합의</span>}
    </>
  );
}

export function HitList({ channel, hits, emptyNote }: Props) {
  if (hits.length === 0) {
    return <p className={`${ui.note} ${ui.noteWarn}`}>0건 — {emptyNote}</p>;
  }
  return (
    <ul className={styles.list}>
      {hits.map((hit, i) => (
        <li key={`${hit.placeId}-${i}`} className={styles.hit}>
          <span className={styles.rank}>{i + 1}</span>
          <div className={styles.name}>
            {hit.brand && hit.label !== hit.name && <span className={styles.brand}>{hit.brand}</span>}
            {hit.name}
            {hit.branch && <em>{hit.branch}</em>}
          </div>
          <div className={styles.cat}>
            {hit.category ?? "–"} · {hit.dong ?? hit.sigungu ?? "–"}
            {hit.distanceM != null && ` · ${hit.distanceM}m`}
          </div>
          {hit.address && <div className={styles.addr}>{hit.address}</div>}
          <div className={styles.badges}>
            <Badges channel={channel} hit={hit} />
          </div>
        </li>
      ))}
    </ul>
  );
}