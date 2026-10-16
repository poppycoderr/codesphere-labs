package shop.order;
/** 订单完成时需要的能力，由订单这一侧按自己的需要定义 */
public interface InvoiceIssuer { String issue(Order o); }
