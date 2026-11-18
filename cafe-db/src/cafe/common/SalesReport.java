package cafe.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 하루치 매출 통계: 메뉴별 판매 수량·매출 + 그날 주문 목록 (취소 주문은 매출에서 제외) */
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

    public final LocalDate date;
    public final List<MenuLine> menuLines;   // 많이 팔린 순
    public final List<Order> orders;         // 매출에 포함되는 주문 (주문 순)
    public final int canceledCount;          // 취소된 주문 수 (참고용)

    public SalesReport(LocalDate date, List<MenuLine> menuLines, List<Order> orders, int canceledCount) {
        this.date = date;
        this.menuLines = Collections.unmodifiableList(new ArrayList<>(menuLines));
        this.orders = Collections.unmodifiableList(new ArrayList<>(orders));
        this.canceledCount = canceledCount;
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
}
