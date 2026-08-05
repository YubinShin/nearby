import { useState } from "react";

import styles from "@/App.module.css";
import { AskView } from "@/features/ask/AskView";
import { CompareView } from "@/features/compare/CompareView";
import { GroundingView } from "@/features/grounding/GroundingView";

const TABS = [
  { id: "ask", label: "자연어 질의", sub: "ask-api 8082" },
  { id: "compare", label: "채널 비교", sub: "search-api 8080" },
  { id: "grounding", label: "그라운딩 실험", sub: "녹화 픽스처" },
] as const;

type TabId = (typeof TABS)[number]["id"];

export function App() {
  const [tab, setTab] = useState<TabId>("ask");
  const [query, setQuery] = useState("역삼동 조용히 공부할 곳");

  return (
    <div className={styles.wrap}>
      <header className={styles.header}>
        <h1>Nearby 콘솔</h1>
        <p>
          자연어 질의가 검색 요청으로 어떻게 바뀌는지(ask-api), 같은 질의를 세 채널이 어떻게 다르게
          찾는지(search-api), 그라운딩 함정 실험이 무엇을 잡았는지를 한 화면에서 봅니다.
        </p>
      </header>

      <nav className={styles.tabs}>
        {TABS.map((entry) => (
          <button
            key={entry.id}
            type="button"
            className={`${styles.tab} ${entry.id === tab ? styles.on : ""}`}
            onClick={() => setTab(entry.id)}
          >
            {entry.label}
            <small>{entry.sub}</small>
          </button>
        ))}
      </nav>

      {tab === "ask" && (
        <AskView
          query={query}
          onQueryChange={setQuery}
          onSendToCompare={(next) => {
            setQuery(next);
            setTab("compare");
          }}
        />
      )}
      {tab === "compare" && <CompareView query={query} onQueryChange={setQuery} />}
      {tab === "grounding" && <GroundingView />}

      <footer className={styles.footer}>
        개발 서버가 <code>/api/search</code> → 8080, <code>/api/ask</code> → 8082 로 프록시합니다.
        그라운딩 탭은 <code>scripts/fixtures/</code> 의 녹화 파일을 빌드 시점에 읽습니다.
      </footer>
    </div>
  );
}