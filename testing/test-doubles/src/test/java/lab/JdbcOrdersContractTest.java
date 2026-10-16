package lab;
class JdbcOrdersContractTest extends RepositoryContract {
    static int n = 0;
    Orders.OrderRepository repo() { return new Orders.JdbcOrders("db" + (n++)); }
}
