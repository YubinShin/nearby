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
     * 세종 품사표 기준, 색인에서 기본으로 제거할 태그 — <b>보수적 정책</b>.
     *
     * <p>1단계에서는 "콘텐츠 품사만 남긴다"는 공격적 정책(접사·관형사·부사·의존명사·보조용언까지 제거)을
     * 기본값으로 삼았다. 4단계에서 검색 경로를 붙이고 상호명 64,239건을 전수 측정하면서 이 결정을 뒤집었다.
     *
     * <p><b>왜 뒤집었나 — 공격적 필터는 오분석과 만나면 글자를 통째로 삼킨다.</b>
     * 형태소 분석기는 미등록 어휘를 자주 틀리게 쪼갠다(예: 상호명 '무도' → 무/NNG + 도/JX).
     * 이때 공격적 정책은 조사로 오인된 '도'를 지워버리고, 원문의 글자가 색인에서 <b>사라진다</b>.
     * 그러면 그 상호는 어떤 질의로도 못 찾는다 — 가장 잡기 어려운 형태의 검색 누락이다.
     *
     * <p>실측(상호명 64,239건, 원문 글자가 색인에서 유실된 비율):
     * <pre>
     *   공격적(1단계 기본값)                 25.17%   (색인량 100%)
     *   보수적(조사·어미·부호·감탄사만)        6.15%   (색인량 108%)
     *   POS 필터 없음                        0.69%   (색인량 115%)
     * </pre>
     * 기여도 상위는 의존명사 NNB(7.20%p) · 체언접두사 XPN(3.44%p) · 부사 MAG(1.94%p) ·
     * 관형사 MM(1.81%p) · 명사파생접미사 XSN(1.55%p) — 전부 공격적 정책에서 추가한 것들이었다.
     *
     * <p>색인량 8% 증가로 유실 12,219건을 되찾는 거래다. 그래서 <b>기본값은 안전한 쪽</b>으로 두고,
     * 문장형 텍스트라 노이즈 제거가 더 중요한 인덱스는 {@code stoptags} 로 공격적 정책을 택하게 한다.
     *
     * <p>남기는 것: 명사 전부(NNG·NNP·NNB·NP·NR), 용언 어간(VV·VA·VX), 관형사·부사(MM·MAG·MAJ),
     * 접사(XPN·XSN·XSV·XSA), 어근(XR), 외국어(SL)·한자(SH)·숫자(SN), 미등록 추정(NF·NV·NA).
     */
    private static final Set<String> DEFAULT_STOP_TAGS = Set.of(
            // 조사 — 검색어로서 가치가 없고, 오분석돼도 원문 글자를 크게 잃지 않는다
            "JKS", "JKC", "JKG", "JKO", "JKB", "JKV", "JKQ", "JX", "JC",
            // 어미
            "EP", "EF", "EC", "ETN", "ETM",
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
