// 被观察的页面代码：每个函数对应一个场景
window.cart = { items: ["a", "b", "c"], total: 3 };

function logCart() {
  console.log(window.cart);
  window.cart.items.push("d");          // 打印之后立即修改
  window.cart.total = 4;
}

function checkout() {
  const count = window.cart.items.length;  // 条件断点设在这一行
  return count;
}

function slowStep() {
  const t0 = performance.now();
  const x = 1 + 1;                          // 断点设在这一行
  const elapsed = performance.now() - t0;
  return elapsed > 500 ? "超时降级" : "正常";
}

function placeOrder() {
  return new Promise((resolve) => {
    const x = new XMLHttpRequest();
    x.open("POST", "/orders");
    x.onload = () => resolve(x.status);
    x.send("{}");
  });
}

function handleExport() {                  // 只在点击时执行
  return "exported";
}

function neverCalled() {
  return "only on some other path";
}

document.getElementById("export").addEventListener("click", handleExport);
