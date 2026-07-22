package dev.yubin.search.komoran.analysis;

import kr.co.shineware.nlp.komoran.core.Komoran;
import kr.co.shineware.nlp.komoran.model.Token;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.List;

/**
 * KOMORAN 형태소 분석 결과를 Lucene 토큰 스트림으로 흘려보내는 Tokenizer.
 *
 * <p>shineware 공식 7.x 구현을 ES 9.x(lucene 10)로 재포팅하면서 두 가지를 고쳤다.
 * <ul>
 *   <li>입력을 {@code BufferedReader.readLine()} 으로 읽어 <b>첫 줄만</b> 분석하던 버그 →
 *       입력 전체를 읽어 분석한다.</li>
 *   <li>Komoran 인스턴스를 토큰 생성마다 새로 만들지 않고(모델 로딩 비용), Factory가 만든
 *       공유 인스턴스를 주입받는다.</li>
 * </ul>
 * 각 토큰의 품사는 {@link TypeAttribute} 로 실어보내며, 뒤단의 POS-stop 필터가 이를 보고
 * 조사·어미 등을 걸러낸다.
 */
public final class KomoranTokenizer extends Tokenizer {

    private final Komoran komoran;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

    private Iterator<Token> tokens;
    private int finalOffset;

    public KomoranTokenizer(Komoran komoran) {
        this.komoran = komoran;
    }

    @Override
    public boolean incrementToken() throws IOException {
        clearAttributes();
        if (tokens == null) {
            String text = readFully(input);
            List<Token> tokenList = komoran.analyze(text).getTokenList();
            tokens = tokenList.iterator();
            finalOffset = text.length();
        }
        if (!tokens.hasNext()) {
            return false;
        }
        Token token = tokens.next();
        termAtt.setEmpty().append(token.getMorph());
        offsetAtt.setOffset(correctOffset(token.getBeginIndex()), correctOffset(token.getEndIndex()));
        posIncrAtt.setPositionIncrement(1);
        typeAtt.setType(token.getPos());
        return true;
    }

    @Override
    public void end() throws IOException {
        super.end();
        offsetAtt.setOffset(correctOffset(finalOffset), correctOffset(finalOffset));
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        tokens = null;
        finalOffset = 0;
    }

    private static String readFully(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[1024];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, read);
        }
        return sb.toString();
    }
}
