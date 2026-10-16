package shop.order;
public class OrderService {
    private final InvoiceIssuer invoices;
    public OrderService(InvoiceIssuer invoices) { this.invoices = invoices; }
    public void complete(Order o) { invoices.issue(o); }
}
