package cafe.common;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 관리자 공지 한 건: 번호, 내용, 올린 시각 (DB notices 테이블 한 줄) */
public class Notice {
    /** 공지 내용 최대 길이 (DB 열 크기와 같음) */
    public static final int MAX_LENGTH = 200;

    public final int no;
    public final String content;
    public final long time;   // 올린 시각 (ms)

    public Notice(int no, String content, long time) {
        this.no = no;
        this.content = content;
        this.time = time;
    }

    /** 통신용: 번호|내용|시각 */
    public String[] toFields() {
        return new String[] { String.valueOf(no), content, String.valueOf(time) };
    }

    public static Notice fromFields(String[] f) {
        return new Notice(Integer.parseInt(f[0]), f[1], Long.parseLong(f[2]));
    }

    /** 화면 표시용 시각. 예) 11/25 10:39 */
    public String timeText() {
        return new SimpleDateFormat("MM/dd HH:mm", Locale.KOREAN).format(new Date(time));
    }
}
