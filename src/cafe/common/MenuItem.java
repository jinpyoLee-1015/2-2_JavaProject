package cafe.common;

public class MenuItem {
    public int id;
    public String category;
    public String name;
    public int price;
    public int stock;
    /** 관리자가 수동으로 품절 처리했는지 */
    public boolean manualSoldOut;

    public MenuItem(int id, String category, String name, int price, int stock, boolean manualSoldOut) {
        this.id = id;
        this.category = category;
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.manualSoldOut = manualSoldOut;
    }

    /** 수동 품절이거나 재고가 0 이하면 품절 */
    public boolean isSoldOut() { return manualSoldOut || stock <= 0; }

    public String[] toFields() {
        return new String[] { String.valueOf(id), category, name, String.valueOf(price),
                String.valueOf(stock), String.valueOf(manualSoldOut) };
    }

    public static MenuItem fromFields(String[] f) {
        return new MenuItem(Integer.parseInt(f[0]), f[1], f[2], Integer.parseInt(f[3]),
                Integer.parseInt(f[4]), Boolean.parseBoolean(f[5]));
    }

    public MenuItem copy() { return new MenuItem(id, category, name, price, stock, manualSoldOut); }
}
