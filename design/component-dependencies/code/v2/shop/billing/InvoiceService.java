package shop.billing;
import shop.order.InvoiceIssuer;
import shop.order.Order;
public class InvoiceService implements InvoiceIssuer {
    public String issue(Order o) { return "INV-" + o.id(); }
}
