// 模拟 OAuth 服务 + 用 Newman CLI 运行几种 token 脚本写法，输出为「键<TAB>事实」。
// POST /token：client_credentials，校验 client_secret，expires_in=3，按 RFC 6749 4.4.3 不返回 refresh_token
// GET /orders：校验 Bearer token 存在且未过期，否则 401
const http = require("http");
const fs = require("fs");
const { spawnSync } = require("child_process");

const SECRET = "example_password";
const tokens = new Map();
let log = [];
let seq = 0;

const server = http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    if (req.method === "POST" && req.url === "/token") {
      const form = new URLSearchParams(body);
      const ok = form.get("grant_type") === "client_credentials" && form.get("client_secret") === SECRET;
      log.push({ path: "/token", status: ok ? 200 : 401 });
      if (!ok) {
        res.writeHead(401, { "Content-Type": "application/json" });
        return res.end(JSON.stringify({ error: "invalid_client" }));
      }
      const token = "t" + ++seq;
      tokens.set(token, Date.now() + 3000);
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ access_token: token, token_type: "Bearer", expires_in: 3 }));
    }
    if (req.method === "GET" && req.url === "/orders") {
      const auth = req.headers.authorization || "";
      const token = auth.replace(/^Bearer /, "");
      const ok = tokens.has(token) && tokens.get(token) > Date.now();
      log.push({ path: "/orders", status: ok ? 200 : 401, auth });
      res.writeHead(ok ? 200 : 401, { "Content-Type": "application/json" });
      return res.end(JSON.stringify(ok ? { orders: [] } : { error: "unauthorized" }));
    }
    res.writeHead(404);
    res.end();
  });
});

// ---------- 集合 ----------

const TOKEN_FORM = (secretExpr) => `[
        { key: "grant_type", value: "client_credentials" },
        { key: "client_id", value: "demo" },
        { key: "client_secret", value: ${secretExpr} }
    ]`;

// 常见写法：每个请求自己的 Pre-request，token 与密钥放全局变量，有 refresh_token 就刷新，失败只打日志
const NAIVE = `
const now = Math.floor(Date.now() / 1000);
const token = pm.globals.get("access_token");
const expiresAt = Number(pm.globals.get("token_expires_at") || 0);
if (!token || now >= expiresAt - 1) {
    const refresh = pm.globals.get("refresh_token");
    const form = refresh
        ? [{ key: "grant_type", value: "refresh_token" }, { key: "refresh_token", value: refresh }]
        : ${TOKEN_FORM('pm.globals.get("client_secret")')};
    pm.sendRequest({ url: "http://127.0.0.1:18080/token", method: "POST", body: { mode: "urlencoded", urlencoded: form } }, (err, res) => {
        if (err || res.code !== 200) { console.log("获取 token 失败", err || res.code); return; }
        const b = res.json();
        pm.globals.set("access_token", b.access_token);
        pm.globals.set("refresh_token", b.refresh_token);
        pm.globals.set("token_expires_at", now + b.expires_in);
        pm.request.headers.upsert({ key: "Authorization", value: "Bearer " + b.access_token });
    });
} else {
    pm.request.headers.upsert({ key: "Authorization", value: "Bearer " + token });
}`;

// 改进写法：集合级脚本，读配置用 pm.variables.get，写 token 用 pm.collectionVariables.set；失败时记失败断言并跳过主请求
const improved = (readSecret, onFailure) => `
const SKEW_SECONDS = 1;
const now = Math.floor(Date.now() / 1000);
const token = pm.collectionVariables.get("access_token");
const expiresAt = Number(pm.collectionVariables.get("token_expires_at") || 0);
if (!token || now >= expiresAt - SKEW_SECONDS) {
    pm.sendRequest({
        url: pm.variables.get("base_url") + "/token",
        method: "POST",
        header: { "Content-Type": "application/x-www-form-urlencoded" },
        body: { mode: "urlencoded", urlencoded: ${TOKEN_FORM(readSecret)} }
    }, (err, response) => {
        if (err || response.code !== 200) {
            pm.collectionVariables.unset("access_token");
            ${onFailure}
            return;
        }
        const body = response.json();
        pm.collectionVariables.set("access_token", body.access_token);
        pm.collectionVariables.set("token_expires_at", now + body.expires_in);
    });
}`;
const FAIL_TEST = `pm.test("获取 token", () => { throw new Error("HTTP " + (err || response.code)); });
            pm.execution.skipRequest();`;
const FAIL_THROW = `throw new Error("HTTP " + (err || response.code));`;

// 顶层 await：Postman 桌面端文档里的写法
const TOP_LEVEL_AWAIT = `
const response = await pm.sendRequest({
    url: pm.variables.get("base_url") + "/token",
    method: "POST",
    body: { mode: "urlencoded", urlencoded: ${TOKEN_FORM('pm.variables.get("client_secret")')} }
});
pm.collectionVariables.set("access_token", response.json().access_token);`;

function collection(name, { perRequest, collectionScript, collectionAuth, variables }) {
  const script = (src) => [{ listen: "prerequest", script: { type: "text/javascript", exec: src.split("\n") } }];
  const items = [];
  for (let i = 1; i <= 8; i++) {
    items.push({
      name: `orders-${i}`,
      event: perRequest ? script(perRequest) : [],
      request: { method: "GET", url: "http://127.0.0.1:18080/orders", ...(collectionAuth ? {} : { auth: { type: "noauth" } }) }
    });
  }
  return {
    info: { name, schema: "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
    item: items,
    event: collectionScript ? script(collectionScript) : [],
    auth: collectionAuth ? { type: "bearer", bearer: [{ key: "token", value: "{{access_token}}", type: "string" }] } : undefined,
    variable: variables || []
  };
}

const BASE = [{ key: "base_url", value: "http://127.0.0.1:18080" }];
const scenarios = [
  ["naive.ok", collection("naive", { perRequest: NAIVE }), ["--global-var", `client_secret=${SECRET}`]],
  ["naive.wrong_secret", collection("naive", { perRequest: NAIVE }), ["--global-var", "client_secret=wrong_password"]],
  ["improved.ok", collection("improved", { collectionScript: improved('pm.variables.get("client_secret")', FAIL_TEST), collectionAuth: true, variables: BASE }), ["--env-var", `client_secret=${SECRET}`]],
  ["improved.wrong_secret", collection("improved", { collectionScript: improved('pm.variables.get("client_secret")', FAIL_TEST), collectionAuth: true, variables: BASE }), ["--env-var", "client_secret=wrong_password"]],
  ["pitfall.collection_scope", collection("scope", { collectionScript: improved('pm.collectionVariables.get("client_secret")', FAIL_TEST), collectionAuth: true,
      variables: [...BASE, { key: "client_secret", value: SECRET }] }), ["--env-var", "client_secret=wrong_password"]],
  ["pitfall.top_level_await", collection("await", { collectionScript: TOP_LEVEL_AWAIT, collectionAuth: true, variables: BASE }), ["--env-var", `client_secret=${SECRET}`]],
  ["pitfall.throw_in_callback", collection("throw", { collectionScript: improved('pm.variables.get("client_secret")', FAIL_THROW), collectionAuth: true, variables: BASE }), ["--env-var", "client_secret=wrong_password"]],
];

function out(key, fact) {
  process.stdout.write(`${key}\t${fact}\n`);
}

server.listen(18080, "127.0.0.1", async () => {
  fs.mkdirSync("build", { recursive: true });
  for (const [key, col, args] of scenarios) {
    log = [];
    const file = `build/${key}.json`;
    const report = `build/${key}.report.json`;
    fs.writeFileSync(file, JSON.stringify(col, null, 2));
    // CLI 在子进程里运行，服务在本进程：用异步 spawn 的同步版本会阻塞事件循环，所以放到 Promise 里
    const code = await new Promise((resolve) => {
      const p = require("child_process").spawn("node_modules/.bin/newman", ["run", file, "--delay-request", "800", "--reporters", "json",
        "--reporter-json-export", report, ...args], { stdio: ["ignore", "ignore", "pipe"] });
      p.on("exit", resolve);
    });
    const r = JSON.parse(fs.readFileSync(report, "utf8")).run;
    const token = log.filter((e) => e.path === "/token");
    const orders = log.filter((e) => e.path === "/orders");
    const scriptErrors = r.failures.filter((f) => f.error && f.error.name && /Error/.test(f.error.name) && f.at && f.at.includes("prerequest"));
    const literal = orders.filter((e) => e.auth === "Bearer {{access_token}}").length;
    out(key, `退出码 ${code}；token 请求 ${token.length} 次（成功 ${token.filter((e) => e.status === 200).length}）；业务请求发出 ${orders.length} 次（200：${orders.filter((e) => e.status === 200).length}，401：${orders.filter((e) => e.status === 401).length}，Authorization 为未替换的 {{access_token}}：${literal}）；`
      + `断言失败 ${r.stats.assertions.failed}；prerequest-scripts 失败 ${r.stats.prerequestScripts.failed}`);
    const first = r.failures[0];
    if (first) {
      out(key + ".first_failure", `${first.error.name}：${String(first.error.message).split("\n")[0].slice(0, 160)}`);
    }
  }
  server.close();
});
