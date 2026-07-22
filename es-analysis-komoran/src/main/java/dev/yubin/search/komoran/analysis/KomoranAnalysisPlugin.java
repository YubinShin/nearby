package dev.yubin.search.komoran.analysis;

import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

import java.util.Map;

/**
 * KOMORAN 한국어 형태소 분석 플러그인 진입점.
 *
 * <p>제공:
 * <ul>
 *   <li>{@code komoran_tokenizer} — KOMORAN 형태소 분석 토크나이저</li>
 *   <li>{@code komoran_pos_stop}  — 품사 기준 불용 토큰 제거 필터 (nori_part_of_speech 대응)</li>
 * </ul>
 */
public class KomoranAnalysisPlugin extends Plugin implements AnalysisPlugin {

    @Override
    public Map<String, AnalysisProvider<TokenizerFactory>> getTokenizers() {
        return Map.of("komoran_tokenizer",
                (indexSettings, environment, name, settings) -> new KomoranTokenizerFactory(name));
    }

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        return Map.of("komoran_pos_stop",
                (indexSettings, environment, name, settings) -> new KomoranPosStopTokenFilterFactory(name, settings));
    }
}
