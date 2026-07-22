package dev.yubin.search.komoran.analysis;

import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code komoran_pos_stop} 필터 팩토리.
 *
 * <p>기본 {@code stopTags} 는 검색에 의미가 낮은 품사(조사·어미·접사·부호·독립언)를 제거한다.
 * 인덱스 설정에서 {@code stoptags} 로 덮어쓸 수 있다.
 *
 * <pre>
 * "filter": {
 *   "my_pos": { "type": "komoran_pos_stop", "stoptags": ["JKS","EC", ...] }
 * }
 * </pre>
 */
public class KomoranPosStopTokenFilterFactory extends AbstractTokenFilterFactory {

    /**
     * 세종 품사표 기준, 색인에서 기본으로 제거할 태그(공격적 정책 — 콘텐츠 품사만 남김).
     * 남기는 것: 명사(NNG·NNP·NP·NR), 용언 어간(VV·VA), 어근(XR), 외국어(SL)·한자(SH)·숫자(SN).
     * 미등록 추정(NF·NV·NA)은 신조어일 수 있어 남긴다.
     */
    private static final Set<String> DEFAULT_STOP_TAGS = Set.of(
            // 조사
            "JKS", "JKC", "JKG", "JKO", "JKB", "JKV", "JKQ", "JX", "JC",
            // 어미
            "EP", "EF", "EC", "ETN", "ETM",
            // 접사 (파생접사는 검색어로서 가치가 낮음)
            "XPN", "XSN", "XSV", "XSA",
            // 지정사 (이다/아니다) — "맛집이다"의 "이" 같은 노이즈
            "VCP", "VCN",
            // 보조용언 — 검색어로서 가치 낮음
            "VX",
            // 관형사·부사 — "새", "자주" 등 검색어 가치 낮음
            "MM", "MAG", "MAJ",
            // 의존명사 — "것", "수" 등 의미 약함
            "NNB",
            // 부호
            "SF", "SP", "SS", "SE", "SO", "SW",
            // 독립언(감탄사)
            "IC"
    );

    private final Set<String> stopTags;

    public KomoranPosStopTokenFilterFactory(String name, Settings settings) {
        super(name);
        List<String> configured = settings.getAsList("stoptags");
        this.stopTags = (configured == null || configured.isEmpty())
                ? DEFAULT_STOP_TAGS
                : new HashSet<>(configured);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return new KomoranPosStopTokenFilter(tokenStream, stopTags);
    }
}
