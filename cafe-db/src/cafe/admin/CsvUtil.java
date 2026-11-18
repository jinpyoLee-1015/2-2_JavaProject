package cafe.admin;

import java.util.ArrayList;
import java.util.List;

/** 최소한의 CSV 읽기/쓰기 (쉼표·따옴표 포함 값 처리) */
final class CsvUtil {
    private CsvUtil() {}

    static String line(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = fields[i] == null ? "" : fields[i];
            if (f.contains(",") || f.contains("\"") || f.contains("\n") || f.contains("\r")) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    /**
     * 엑셀·한셀이 숫자나 날짜로 바꾸지 않고 글자 그대로 보여 주도록 감싼다. (="값" 형태)
     * 예) 학번 2023123456 이 2.023E+09 로, 날짜가 ####### 로 보이는 문제를 막는다.
     */
    static String text(String s) {
        return "=\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    static String[] parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else cur.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
