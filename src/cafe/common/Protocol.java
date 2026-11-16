package cafe.common;

import java.util.ArrayList;
import java.util.List;

/** 서버-클라이언트 통신 규약 (한 줄 = 한 메시지, 필드는 '|' 로 구분). */
public final class Protocol {
    public static final int DEFAULT_PORT = 5050;
    /** 주문 1건당 예상 제조 시간(분) */
    public static final int MINUTES_PER_ORDER = 3;
    public static final String SIZE_BASIC = "기본";
    public static final String SIZE_LARGE = "라지";
    public static final int SIZE_UP_PRICE = 500;
    public static final int SHOT_PRICE = 500;
    public static final int MAX_SHOTS = 3;
    public static final int MAX_QTY = 10;

    private Protocol() {}

    public static String pack(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('|');
            String f = fields[i] == null ? "" : fields[i];
            for (int k = 0; k < f.length(); k++) {
                char c = f.charAt(k);
                switch (c) {
                    case '\\': sb.append("\\\\"); break;
                    case '|':  sb.append("\\p"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    default:   sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String[] unpack(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '|') {
                out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                switch (n) {
                    case 'p': cur.append('|'); break;
                    case 'n': cur.append('\n'); break;
                    case 'r': cur.append('\r'); break;
                    default:  cur.append(n);
                }
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /** 사용자 입력 정리: 제어문자 제거 + 앞뒤 공백 제거 */
    public static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
