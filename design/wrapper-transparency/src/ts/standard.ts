// 当前标准装饰器：(value, context)
function logged<This, Args extends unknown[], R>(value: (this: This, ...args: Args) => R, context: ClassMethodDecoratorContext) {
  return function (this: This, ...args: Args): R {
    console.log(`standard\t调用 ${String(context.name)}(${args.join(', ')})`);
    return value.apply(this, args);
  };
}
class Account {
  constructor(private balance: number) {}
  @logged
  withdraw(n: number) { this.balance -= n; return this.balance; }
}
console.log(`standard\t结果 ${new Account(100).withdraw(30)}`);
