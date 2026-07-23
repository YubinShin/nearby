package dev.yubin.search.komoran.analysis;

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * {@code komoran_tokenizer} 를 만드는 팩토리.
 *
 * <p>Komoran 모델 로딩(수 MB)은 무거우므로 인덱스당 <b>한 번</b>만 하고, 생성된 인스턴스를
 * 모든 Tokenizer가 공유한다. Komoran.analyze() 는 호출마다 독립적인 lattice 를 만들어 결과를
 * 돌려주므로 읽기 전용 모델을 공유해도 안전하다.
 *
 * <p><b>사전 설정</b> — 경로는 모두 Elasticsearch <b>config 디렉토리 기준 상대경로</b>다.
 * <pre>
 * "tokenizer": {
 *   "komoran_tokenizer": {
 *     "type": "komoran_tokenizer",
 *     "user_dictionary": "analysis/komoran/place.dict",   // 단어\tPOS
 *     "fw_dictionary":   "analysis/komoran/place.fwd"     // 표층형\t형태소/품사 …
 *   }
 * }
 * </pre>
 *
 * <p>사전은 <b>색인과 검색이 반드시 같은 파일</b>을 봐야 한다. 한쪽만 바뀌면 같은 글자가 서로 다르게
 * 쪼개져 매칭이 조용히 깨진다. 그래서 사전을 갈아끼울 땐 alias 스왑으로 전체 재색인하는 것이 원칙이다
 * (ADR 0002·0008).
 */
public class KomoranTokenizerFactory extends AbstractTokenizerFactory {

    private final Komoran komoran;

    public KomoranTokenizerFactory(Environment environment, String name, Settings settings) {
        super(name);
        // STABLE = 경량 모델(models_light). 정확도 우선이 필요하면 후속 단계에서 모델 선택을 설정화한다.
        this.komoran = new Komoran(DEFAULT_MODEL.STABLE);

        // 사용자 사전: 미등록 어휘를 등록해 오분석을 막는다 (예: '논현' 없이는 놓/ㄴ/현 으로 깨짐).
        applyDictionary(environment, settings, "user_dictionary", komoran::setUserDic);
        // 기분석 사전: "이 표층형은 무조건 이렇게 분석하라"고 못 박는다. 사용자 사전으로도 안 잡히는
        // 문맥 의존 오분석에 쓴다.
        applyDictionary(environment, settings, "fw_dictionary", komoran::setFWDic);
    }

    /**
     * config 디렉토리 안의 사전 파일을 찾아 Komoran 에 물린다.
     *
     * <p>설정이 없으면 아무것도 하지 않는다(사전 없이도 동작). 반대로 설정이 있는데 파일이 없으면
     * <b>기동을 실패시킨다</b> — 사전이 조용히 무시되면 색인·검색이 서로 다른 사전을 보게 되고,
     * 그건 "검색이 가끔 안 된다"는 가장 잡기 어려운 형태의 장애가 된다.
     */
    private static void applyDictionary(Environment environment, Settings settings, String key, Consumer<String> apply) {
        String configured = settings.get(key);
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path configDir = environment.configDir().toAbsolutePath().normalize();
        Path dictionary = configDir.resolve(configured).toAbsolutePath().normalize();

        // config 바깥을 가리키는 경로(../../etc/passwd 등)는 거부한다.
        if (!dictionary.startsWith(configDir)) {
            throw new IllegalArgumentException(
                    "[" + key + "] 는 Elasticsearch config 디렉토리 안이어야 합니다: " + configured);
        }
        if (!Files.isReadable(dictionary)) {
            throw new IllegalArgumentException(
                    "[" + key + "] 사전 파일을 읽을 수 없습니다: " + dictionary);
        }
        apply.accept(dictionary.toString());
    }

    @Override
    public Tokenizer create() {
        return new KomoranTokenizer(komoran);
    }
}
