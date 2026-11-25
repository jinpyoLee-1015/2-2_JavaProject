package cafe.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 기간 매출 통계 (하루 / 일주일 / 한 달): 메뉴별 판매 수량·매출 + 날짜별·시간대별 매출 + 주문 목록.
 * 취소 주문은 매출에서 빼고 건수만 따로 센다.
 */
public class SalesReport {

    /** 메뉴 한 줄: 메뉴 이름, 판매 수량(잔), 매출 */
    public static class MenuLine {
        public final String menuName;
        public final int qty;
        public final int amount;

        public MenuLine(String menuName, int qty, int amount) {
            this.menuName = menuName;
            this.qty = qty;
            this.amount = amount;
        }
    }

    /** 날짜 한 줄: 날짜, 주문 건수, 판매 수량(잔), 매출 (주문이 있는 날만) */
    public static class DayLine {
        public final LocalDate date;
        public final int orderCount;
        public final int qty;
        public final int amount;

        public DayLine(LocalDate date, int orderCount, int qty, int amount) {
            this.date = date;
            this.orderCount = orderCount;
            this.qty = qty;
            this.amount = amount;
        }
    }

    /** 시간대 한 줄: 시(0~23), 주문 건수, 판매 수량(잔), 매출 (주문이 있는 시간대만) */
    public static class HourLine {
        public final int hour;
        public final int orderCount;
        public final int qty;
        public final int amount;

        public HourLine(int hour, int orderCount, int qty, int amount) {
            this.hour = hour;
            this.orderCount = orderCount;
            this.qty = qty;
            this.amount = amount;
        }

        /** 예) 12시~13시 */
        public String label() { return String.format("%02d시~%02d시", hour, hour + 1); }
    }

    public final LocalDate from;             // 시작 날짜 (포함)
    public final LocalDate to;               // 끝 날짜 (포함)
    public final LocalDate date;             // = from (하루 통계용 예전 이름)
    public final List<MenuLine> menuLines;   // 많이 팔린 순
    public final List<DayLine> dayLines;     // 날짜 순
    public final List<HourLine> hourLines;   // 시간 순 (기간 전체를 시간대별로 합친 것)
    public final List<Order> orders;         // 매출에 포함되는 주문 (주문 순)
    public final int canceledCount;          // 취소된 주문 수 (참고용)

    public SalesReport(LocalDate from, LocalDate to, List<MenuLine> menuLines, List<DayLine> dayLines,
                       List<HourLine> hourLines, List<Order> orders, int canceledCount) {
        this.from = from;
        this.to = to;
        this.date = from;
        this.menuLines = Collections.unmodifiableList(new ArrayList<>(menuLines));
        this.dayLines = Collections.unmodifiableList(new ArrayList<>(dayLines));
        this.hourLines = Collections.unmodifiableList(new ArrayList<>(hourLines));
        this.orders = Collections.unmodifiableList(new ArrayList<>(orders));
        this.canceledCount = canceledCount;
    }

    /** 하루치 통계 (예전 생성자) */
    public SalesReport(LocalDate date, List<MenuLine> menuLines, List<Order> orders, int canceledCount) {
        this(date, date, menuLines, Collections.emptyList(), Collections.emptyList(), orders, canceledCount);
    }

    /** 총 매출 */
    public int totalAmount() {
        int t = 0;
        for (MenuLine m : menuLines) t += m.amount;
        return t;
    }

    /** 총 판매 수량(잔) */
    public int totalQty() {
        int t = 0;
        for (MenuLine m : menuLines) t += m.qty;
        return t;
    }

    public int orderCount() { return orders.size(); }

    /** 주문이 있었던 날 수 (영업일) */
    public int salesDays() { return dayLines.size(); }

    /** 영업일 하루 평균 매출 (주문이 없으면 0) */
    public int averagePerDay() { return dayLines.isEmpty() ? 0 : totalAmount() / dayLines.size(); }

    /** 주문이 가장 많았던 시간대 (없으면 null) */
    public HourLine busiestHour() {
        HourLine best = null;
        for (HourLine h : hourLines) if (best == null || h.orderCount > best.orderCount) best = h;
        return best;
    }

    /** 시간대 주문 건수 합계 (비중 계산용) */
    public int hourOrderTotal() {
        int t = 0;
        for (HourLine h : hourLines) t += h.orderCount;
        return t;
    }

    /** 매출이 가장 많았던 날 (없으면 null) */
    public DayLine bestDay() {
        DayLine best = null;
        for (DayLine d : dayLines) if (best == null || d.amount > best.amount) best = d;
        return best;
    }
}
