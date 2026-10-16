package shop.billing;
import shop.order.Order;
public class InvoiceService {
    public String issue(Order o) { return "INV-" + o.id(); }
}
