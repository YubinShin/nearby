import { useState } from "react";

import {
  type ContextPlace,
  type GroundingAnswer,
  type GroundingExperiment,
  RUNS,
} from "@/features/grounding/fixtures";
import styles from "@/features/grounding/GroundingView.module.css";
import ui from "@/shared/styles/ui.module.css";

export function GroundingView() {
  const [runId, setRunId] = useState(RUNS[0]?.id ?? "");
  const [selected, setSelected] = useState(RUNS[0]?.experiments[0]?.id ?? "");

  if (RUNS.length === 0) {
    return (
      <p className={`${ui.panel} ${ui.note}`}>
        녹화된 실험이 없습니다. <code>python3 scripts/grounding_experiments.py</code> 로 기록하십시오.
      </p>
    );
  }

  const run = RUNS.find((candidate) => candidate.id === runId) ?? RUNS[0];
  const experiment =
    run.experiments.find((candidate) => candidate.id === selected) ?? run.experiments[0];
  const passed = run.experiments.filter((candidate) => candidate.verdict === "PASS").length;

  return (
    <>
      <div className={`${ui.panel} ${styles.runbar}`}>
        <span className={ui.chipsLabel}>실행</span>
        {RUNS.map((candidate) => (
          <button
            key={candidate.id}
            type="button"
            className={ui.chip}
            style={candidate.id === run.id ? { borderColor: "var(--ink)", color: "var(--ink)" } : undefined}
            onClick={() => {
              setRunId(candidate.id);
              setSelected(candidate.experiments[0]?.id ?? "");
            }}
          >
            {candidate.id}
          </button>
        ))}
        <span className={styles.spacer} />
        <span className={ui.tag}>{run.model ?? "모델 미상"}</span>
        <span className={ui.tag}>thinking {run.thinkingLevel ?? "–"}</span>
        <span className={`${ui.tag} ${passed === run.experiments.length ? ui.tagOk : ui.tagBad}`}>
          PASS {passed}/{run.experiments.length}
        </span>
      </div>

      <div className={styles.split}>
        <section className={ui.panel}>
          <h2 className={ui.head}>
            함정 실험
            <span className={ui.sub}>{run.runAt ? run.runAt.slice(0, 16).replace("T", " ") : run.id}</span>
          </h2>
          <ul className={styles.list}>
            {run.experiments.map((candidate) => (
              <li key={candidate.id}>
                <button
                  type="button"
                  className={`${styles.item} ${candidate.id === experiment.id ? styles.on : ""}`}
                  onClick={() => setSelected(candidate.id)}
                >
                  <span className={styles.itemId}>{candidate.id}</span>
                  <Verdict verdict={candidate.verdict} findings={candidate.findings.length} />
                  {candidate.note && <span className={styles.itemNote}>{candidate.note}</span>}
                </button>
              </li>
            ))}
          </ul>
        </section>

        <section className={ui.panel}>
          <h2 className={ui.head}>
            {experiment.id}
            <span className={ui.sub}>{experiment.modelVersion}</span>
            <span className={ui.meta}>{experiment.recordedAt.slice(0, 19).replace("T", " ")}</span>
          </h2>
          <Detail key={experiment.id} experiment={experiment} />
        </section>
      </div>
    </>
  );
}

function Verdict({ verdict, findings }: { verdict: string; findings: number }) {
  const tone = verdict === "PASS" ? ui.tagOk : verdict === "UNKNOWN" ? ui.tag : ui.tagBad;
  return (
    <span className={`${ui.tag} ${tone}`}>
      {verdict}
      {findings > 0 && ` · ${findings}`}
    </span>
  );
}

function Detail({ experiment }: { experiment: GroundingExperiment }) {
  const answer = experiment.answer;

  return (
    <>
      <div className={styles.section}>
        <p className={styles.sectionTitle}>질문</p>
        <p className={styles.question}>{experiment.question}</p>
      </div>

      {answer === null ? (
        <div className={styles.section}>
          <p className={`${ui.note} ${ui.noteBad}`} style={{ padding: 0 }}>
            응답을 해석하지 못했습니다.
          </p>
        </div>
      ) : (
        <>
          <div className={styles.section}>
            <p className={styles.sectionTitle}>근거 추적 · 문장을 짚으면 인용한 레코드가 켜집니다</p>
            <EvidenceTrace experiment={experiment} answer={answer} />
          </div>

          <div className={styles.section}>
            <p className={styles.sectionTitle}>확인 불가 조건 · unverifiable_conditions</p>
            {answer.unverifiable_conditions.length === 0 ? (
              <span className={ui.dim}>없음</span>
            ) : (
              <div className={ui.chips}>
                {answer.unverifiable_conditions.map((condition) => (
                  <span key={condition} className={`${ui.tag} ${ui.tagWarn}`}>
                    {condition}
                  </span>
                ))}
              </div>
            )}
          </div>
        </>
      )}

      <div className={styles.section}>
        <p className={styles.sectionTitle}>검증 결과 · {experiment.verdict}</p>
        {experiment.findings.length === 0 ? (
          <span className={ui.dim}>지적 없음</span>
        ) : (
          <ul className={styles.findings}>
            {experiment.findings.map((finding, i) => (
              <li key={i}>{finding}</li>
            ))}
          </ul>
        )}
      </div>

      {experiment.usage && (
        <div className={styles.section}>
          <p className={styles.sectionTitle}>토큰</p>
          <div className={styles.usage}>
            <span>prompt {experiment.usage.promptTokenCount ?? "–"}</span>
            <span>candidates {experiment.usage.candidatesTokenCount ?? "–"}</span>
            <span>thoughts {experiment.usage.thoughtsTokenCount ?? 0}</span>
            <span>total {experiment.usage.totalTokenCount ?? "–"}</span>
          </div>
        </div>
      )}
    </>
  );
}

type Active = { kind: "sentence"; index: number } | { kind: "place"; id: string } | null;

function EvidenceTrace({
  experiment,
  answer,
}: {
  experiment: GroundingExperiment;
  answer: GroundingAnswer;
}) {
  const [active, setActive] = useState<Active>(null);

  const byId = new Map(experiment.places.map((place) => [place.id, place]));
  const grounded = answer.sentences.filter((sentence) => sentence.evidence.length > 0).length;
  const cited = new Set(
    answer.sentences.flatMap((sentence) => sentence.evidence).filter((id) => byId.has(id)),
  );

  const activePlaces = new Set<string>();
  const activeSentences = new Set<number>();
  if (active?.kind === "sentence") {
    activeSentences.add(active.index);
    for (const id of answer.sentences[active.index].evidence) activePlaces.add(id);
  } else if (active?.kind === "place") {
    activePlaces.add(active.id);
    answer.sentences.forEach((sentence, i) => {
      if (sentence.evidence.includes(active.id)) activeSentences.add(i);
    });
  }

  return (
    <>
      <div className={styles.summary}>
        <span className={`${ui.tag} ${answer.found ? ui.tagOk : ui.tagWarn}`}>
          found {String(answer.found)}
        </span>
        {experiment.finishReason && (
          <span className={`${ui.tag} ${experiment.finishReason === "STOP" ? "" : ui.tagBad}`}>
            finishReason {experiment.finishReason}
          </span>
        )}
        <span className={`${ui.tag} ${grounded === answer.sentences.length ? "" : ui.tagBad}`}>
          근거 있는 문장 {grounded}/{answer.sentences.length}
        </span>
        <span className={ui.tag}>
          인용된 레코드 {cited.size}/{experiment.places.length}
        </span>
      </div>

      <div className={styles.trace} onMouseLeave={() => setActive(null)}>
        <div>
          {answer.sentences.length === 0 ? (
            <p className={ui.dim}>문장 없음</p>
          ) : (
            answer.sentences.map((sentence, i) => (
              <div
                key={i}
                className={[
                  styles.sentence,
                  activeSentences.has(i) ? styles.on : "",
                  active && !activeSentences.has(i) ? styles.off : "",
                  sentence.evidence.length === 0 ? styles.orphan : "",
                ].join(" ")}
                onMouseEnter={() => setActive({ kind: "sentence", index: i })}
              >
                <div>{sentence.text}</div>
                <div className={styles.evidence}>
                  {sentence.evidence.length === 0 ? (
                    <span className={`${ui.tag} ${ui.tagBad}`}>근거 없음 · 환각 후보</span>
                  ) : (
                    sentence.evidence.map((id) => (
                      <EvidenceTag key={id} id={id} place={byId.get(id)} />
                    ))
                  )}
                </div>
              </div>
            ))
          )}
        </div>

        <div>
          {experiment.places.length === 0 ? (
            <p className={`${ui.note} ${ui.noteWarn}`} style={{ padding: 0 }}>
              컨텍스트 0건 — 근거로 쓸 레코드가 없습니다.
            </p>
          ) : (
            <PlaceTable
              places={experiment.places}
              activePlaces={activePlaces}
              dimOthers={active !== null}
              cited={cited}
              onHover={(id) => setActive(id ? { kind: "place", id } : null)}
            />
          )}
        </div>
      </div>
    </>
  );
}

function PlaceTable({
  places,
  activePlaces,
  dimOthers,
  cited,
  onHover,
}: {
  places: ContextPlace[];
  activePlaces: Set<string>;
  dimOthers: boolean;
  cited: Set<string>;
  onHover: (id: string | null) => void;
}) {
  return (
    <table className={styles.places}>
      <thead>
        <tr>
          <th>place_id</th>
          <th>상호</th>
          <th>업종</th>
          <th>인용</th>
        </tr>
      </thead>
      <tbody>
        {places.map((place) => (
          <tr
            key={place.id}
            className={[
              activePlaces.has(place.id) ? styles.on : "",
              dimOthers && !activePlaces.has(place.id) ? styles.off : "",
            ].join(" ")}
            onMouseEnter={() => onHover(place.id)}
          >
            <td className={styles.id}>{place.id}</td>
            <td>{place.name}</td>
            <td>{place.category}</td>
            <td>{cited.has(place.id) ? "●" : ""}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function EvidenceTag({ id, place }: { id: string; place: ContextPlace | undefined }) {
  if (!place) {
    return <span className={`${ui.tag} ${ui.tagBad}`}>{id} · 컨텍스트 밖</span>;
  }
  return <span className={ui.tag}>{place.name}</span>;
}