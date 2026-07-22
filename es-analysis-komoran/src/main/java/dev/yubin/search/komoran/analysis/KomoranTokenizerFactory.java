package dev.yubin.search.komoran.analysis;

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;

/**
 * {@code komoran_tokenizer} 를 만드는 팩토리.
 *
 * <p>Komoran 모델 로딩(수 MB)은 무거우므로 인덱스당 <b>한 번</b>만 하고, 생성된 인스턴스를
 * 모든 Tokenizer가 공유한다. Komoran.analyze() 는 호출마다 독립적인 lattice 를 만들어 결과를
 * 돌려주므로 읽기 전용 모델을 공유해도 안전하다.
 */
public class KomoranTokenizerFactory extends AbstractTokenizerFactory {

    private final Komoran komoran;

    public KomoranTokenizerFactory(String name) {
        super(name);
        // STABLE = 경량 모델(models_light). 정확도 우선이 필요하면 후속 단계에서 모델 선택을 설정화한다.
        this.komoran = new Komoran(DEFAULT_MODEL.STABLE);
    }

    @Override
    public Tokenizer create() {
        return new KomoranTokenizer(komoran);
    }
}
