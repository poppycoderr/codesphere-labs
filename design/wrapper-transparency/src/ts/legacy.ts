// experimentalDecorators：(target, key, descriptor)
function logged(target: object, key: string, descriptor: PropertyDescriptor) {
  const original = descriptor.value;
  descriptor.value = function (this: unknown, ...args: unknown[]) {
    console.log(`legacy\t调用 ${key}(${args.join(', ')})`);
    return original.apply(this, args);
  };
  return descriptor;
}
class Account {
  constructor(private balance: number) {}
  @logged
  withdraw(n: number) { this.balance -= n; return this.balance; }
}
console.log(`legacy\t结果 ${new Account(100).withdraw(30)}`);
