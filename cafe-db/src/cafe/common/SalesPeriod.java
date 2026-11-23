package cafe.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 매출 통계를 묶는 단위: 일별 / 주별(월~일) / 월별.
 * 기간은 '시작 날짜' 하나로 나타내고, 끝 날짜는 end(start) 로 구한다.
 */
public enum SalesPeriod {
    DAY("일별"),
    WEEK("주별"),
    MONTH("월별");

    public final String label;

    SalesPeriod(String label) { this.label = label; }

    @Override public String toString() { return label; }

    /** 날짜가 들어 있는 기간의 시작 날짜 (주는 월요일, 월은 1일) */
    public LocalDate start(LocalDate d) {
        switch (this) {
            case WEEK: return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH: return d.withDayOfMonth(1);
            default: return d;
        }
    }

    /** 기간의 마지막 날짜 (그날 포함) */
    public LocalDate end(LocalDate start) {
        switch (this) {
            case WEEK: return start.plusDays(6);
            case MONTH: return start.with(TemporalAdjusters.lastDayOfMonth());
            default: return start;
        }
    }

    public boolean contains(LocalDate start, LocalDate d) {
        return !d.isBefore(start) && !d.isAfter(end(start));
    }

    /** 주문이 있는 날짜 목록 → 기간 시작 날짜 목록 (중복 제거, 순서 유지). 오늘이 든 기간은 항상 맨 앞에 넣는다. */
    public List<LocalDate> starts(List<LocalDate> orderDates, LocalDate today) {
        List<LocalDate> list = new ArrayList<>();
        list.add(start(today));
        for (LocalDate d : orderDates) {
            LocalDate s = start(d);
            if (!list.contains(s)) list.add(s);
        }
        list.sort((a, b) -> b.compareTo(a));   // 최근 기간이 먼저
        return list;
    }

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E)", Locale.KOREAN);
    private static final DateTimeFormatter SHORT_FMT = DateTimeFormatter.ofPattern("MM월 dd일 (E)", Locale.KOREAN);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy년 MM월", Locale.KOREAN);

    /** 화면·CSV 에 보여 줄 기간 이름. 예) 2026년 11월 23일 (월) ~ 11월 29일 (일) */
    public String describe(LocalDate start) {
        switch (this) {
            case WEEK: return start.format(DAY_FMT) + " ~ " + end(start).format(SHORT_FMT);
            case MONTH: return start.format(MONTH_FMT);
            default: return start.format(DAY_FMT);
        }
    }

    /** 오늘이 든 기간이면 "오늘 / 이번 주 / 이번 달" */
    public String currentTag() {
        switch (this) {
            case WEEK: return "이번 주";
            case MONTH: return "이번 달";
            default: return "오늘";
        }
    }

    /** CSV 파일 이름용. 예) 20261123, 20261123-20261129, 202611 */
    public String fileTag(LocalDate start) {
        DateTimeFormatter ymd = DateTimeFormatter.ofPattern("yyyyMMdd");
        switch (this) {
            case WEEK: return start.format(ymd) + "-" + end(start).format(ymd);
            case MONTH: return start.format(DateTimeFormatter.ofPattern("yyyyMM"));
            default: return start.format(ymd);
        }
    }
}
