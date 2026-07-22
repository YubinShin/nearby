package dev.yubin.search.komoran.analysis;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.Set;

/**
 * 품사(POS) 기준으로 토큰을 걸러내는 필터. KomoranTokenizer 가 {@link TypeAttribute} 에 실어둔
 * 세종 품사 태그가 {@code stopTags} 에 속하면 그 토큰을 버린다 (예: 조사 JK*, 어미 E*).
 *
 * <p>nori 의 {@code nori_part_of_speech} 필터에 대응한다. lucene-analysis-common 의
 * FilteringTokenFilter 대신 core 의 {@link TokenFilter} 를 직접 상속해, 플러그인이 lucene core
 * 외의 의존을 갖지 않도록 했다.
 */
public final class KomoranPosStopTokenFilter extends TokenFilter {

    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);
    private final Set<String> stopTags;

    public KomoranPosStopTokenFilter(TokenStream input, Set<String> stopTags) {
        super(input);
        this.stopTags = stopTags;
    }

    @Override
    public boolean incrementToken() throws IOException {
        while (input.incrementToken()) {
            if (!stopTags.contains(typeAtt.type())) {
                return true;
            }
        }
        return false;
    }
}
