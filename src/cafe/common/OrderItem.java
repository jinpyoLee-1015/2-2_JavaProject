package cafe.common;

import java.util.ArrayList;
import java.util.List;

/** 주문(장바구니) 한 줄: 메뉴 + 옵션 + 수량 */
public class OrderItem {
    public final int menuId;
    public final String name;
    public final String size;
    public final int shots;
    public final int qty;
    public final int unitPrice;

    public OrderItem(int menuId, String name, String size, int shots, int qty, int unitPrice) {
        this.menuId = menuId;
        this.name = name;
        this.size = size;
        this.shots = shots;
        this.qty = qty;
        this.unitPrice = unitPrice;
    }

    public static int calcUnitPrice(int basePrice, String size, int shots) {
        return basePrice + (Protocol.SIZE_LARGE.equals(size) ? Protocol.SIZE_UP_PRICE : 0)
                + shots * Protocol.SHOT_PRICE;
    }

    public int lineTotal() { return unitPrice * qty; }

    public String optionText() {
        StringBuilder sb = new StringBuilder(size);
        if (shots > 0) sb.append(", 샷 +").append(shots);
        return sb.toString();
    }

    public String summary() { return name + "(" + optionText() + ") x" + qty; }

    public boolean sameAs(OrderItem o) {
        return menuId == o.menuId && size.equals(o.size) && shots == o.shots;
    }

    public String pack() {
        return Protocol.pack(String.valueOf(menuId), name, size, String.valueOf(shots),
                String.valueOf(qty), String.valueOf(unitPrice));
    }

    public static OrderItem unpack(String s) {
        String[] f = Protocol.unpack(s);
        return new OrderItem(Integer.parseInt(f[0]), f[1], f[2], Integer.parseInt(f[3]),
                Integer.parseInt(f[4]), Integer.parseInt(f[5]));
    }

    public static String packList(List<OrderItem> items) {
        String[] arr = new String[items.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = items.get(i).pack();
        return Protocol.pack(arr);
    }

    public static List<OrderItem> unpackList(String s) {
        List<OrderItem> list = new ArrayList<>();
        if (s == null || s.isEmpty()) return list;
        for (String one : Protocol.unpack(s)) {
            if (!one.isEmpty()) list.add(unpack(one));
        }
        return list;
    }
}
