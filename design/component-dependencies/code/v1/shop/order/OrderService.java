package shop.order;
import shop.billing.InvoiceService;
public class OrderService {
    private final InvoiceService invoices = new InvoiceService();
    public void complete(Order o) { invoices.issue(o); }
}
