package cafe.admin;

/** 엑셀용 CSV 쓰기 도구 (쉼표·따옴표·줄바꿈이 들어간 값 처리) */
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
}
