package shop.order;
import shop.common.Money;
public class OrderService {
    private final InvoiceIssuer invoices;
    public OrderService(InvoiceIssuer invoices) { this.invoices = invoices; }
    public void complete(Order o) { invoices.issue(o); }
    public String display(Order o) { return o.id() + " " + Money.of(o); }
}
