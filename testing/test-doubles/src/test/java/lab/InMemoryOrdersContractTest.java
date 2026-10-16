package lab;
class InMemoryOrdersContractTest extends RepositoryContract { Orders.OrderRepository repo() { return new Orders.InMemoryOrders(); } }
