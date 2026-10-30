-- 保存时格式化，谁说了算：两个格式化服务、按名字过滤、同步超时、异步结果返回前又改了内容、BufWritePre 里异步格式化、已移除的旧接口
local function out(key, text) io.stdout:write(key .. "\t" .. text .. "\n"); io.stdout:flush() end
local SRC = { "def f():", "\tif x:", "\t\treturn 1", "" }
local n = 0

local function show(buf)
  local lines = vim.api.nvim_buf_get_lines(buf, 0, -1, false)
  local parts = {}
  for i = 2, 3 do
    local ws = lines[i]:match("^(%s*)")
    table.insert(parts, #ws)
  end
  return "缩进 " .. table.concat(parts, "/")
end

local function new_buf(servers)
  n = n + 1
  local path = "/tmp/p/f" .. n .. ".py"
  vim.fn.writefile(SRC, path)
  vim.cmd("edit " .. path)
  local buf = vim.api.nvim_get_current_buf()
  for _, s in ipairs(servers) do
    -- 名字加上序号：同名、同根目录的服务会被 vim.lsp.start 复用
    vim.lsp.start({ name = s.name .. "#" .. n, cmd = { "python3", "/src/fmt_server.py", tostring(s.indent), tostring(s.delay or 0) }, root_dir = "/tmp/p" }, { bufnr = buf })
  end
  vim.wait(5000, function() return #vim.lsp.get_clients({ bufnr = buf }) == #servers and vim.iter(vim.lsp.get_clients({ bufnr = buf })):all(function(c) return c.initialized end) end)
  return buf, path
end

vim.fn.mkdir("/tmp/p", "p")
local two = { { name = "fmt2", indent = 2 }, { name = "fmt4", indent = 4 } }

-- 一、两个服务都能格式化
local buf = new_buf(two)
local names = {}
for _, c in ipairs(vim.lsp.get_clients({ bufnr = buf })) do
  table.insert(names, c.name:gsub("#.*", "") .. "=" .. tostring(c.server_capabilities.documentFormattingProvider))
end
table.sort(names)
out("clients", "同一个缓冲区挂了 2 个服务，都声明能格式化：" .. table.concat(names, "、"))
vim.lsp.buf.format({ bufnr = buf, timeout_ms = 3000 })
out("both", "vim.lsp.buf.format() 不加过滤：" .. show(buf) .. "（两个服务依次格式化，后一个的结果覆盖前一个）")
buf = new_buf(two)
vim.lsp.buf.format({ bufnr = buf, timeout_ms = 3000, name = "fmt2#" .. n })
out("filter.name", "按名字只用 fmt2：" .. show(buf))
buf = new_buf(two)
vim.lsp.buf.format({ bufnr = buf, timeout_ms = 3000, filter = function(c) return vim.startswith(c.name, "fmt4#") end })
out("filter.fn", "用 filter 只用 fmt4：" .. show(buf))

-- 二、同步格式化超时
buf = new_buf({ { name = "slow", indent = 4, delay = 1500 } })
local ok, err = pcall(vim.lsp.buf.format, { bufnr = buf, timeout_ms = 300 })
local msg = ok and "无错误" or tostring(err):gsub("\n.*", "")
vim.wait(100)
out("sync.timeout", "服务要 1.5 秒、同步格式化 timeout_ms=300：调用返回（" .. msg .. "），缓冲区 " .. show(buf))
vim.wait(1600)
out("sync.late", "超时之后结果再回来：缓冲区 " .. show(buf) .. "（迟到的结果被丢弃）")

-- 三、异步格式化返回前用户又改了内容
buf = new_buf({ { name = "slow", indent = 4, delay = 500 } })
vim.lsp.buf.format({ bufnr = buf, async = true })
vim.api.nvim_buf_set_lines(buf, 0, 1, false, { "def g():" })
vim.wait(2000)
local first = vim.api.nvim_buf_get_lines(buf, 0, 1, false)[1]
out("async.changed", "async=true 发出请求后立即把第 1 行改成「def g():」：0.5 秒后结果返回，缓冲区第 1 行「" .. first .. "」，" .. show(buf) .. "；用户的修改被格式化结果覆盖=" .. tostring(first ~= "def g():"))
buf = new_buf({ { name = "slow", indent = 4, delay = 500 } })
vim.lsp.buf.format({ bufnr = buf, async = true })
vim.wait(2000, function() return vim.api.nvim_buf_get_lines(buf, 1, 2, false)[1]:sub(1, 1) == " " end)
out("async.ok", "同样的异步格式化、期间不改内容：" .. show(buf))

-- 四、保存时格式化：BufWritePre 里同步与异步
local function save_with(async)
  local b, path = new_buf({ { name = "slow", indent = 4, delay = 300 } })
  local g = vim.api.nvim_create_augroup("fmt" .. n, {})
  vim.api.nvim_create_autocmd("BufWritePre", { group = g, buffer = b, callback = function() vim.lsp.buf.format({ bufnr = b, async = async, timeout_ms = 2000 }) end })
  vim.cmd("silent write")
  vim.wait(1500)
  local disk = vim.fn.readfile(path)
  local ws = #(disk[2]:match("^(%s*)"))
  return ws, show(b), vim.bo[b].modified
end
local ws1, s1, m1 = save_with(false)
out("save.sync", "BufWritePre 里同步格式化后 :write：磁盘上第 2 行缩进 " .. ws1 .. "，缓冲区 " .. s1 .. "，未保存的修改=" .. tostring(m1))
local ws2, s2, m2 = save_with(true)
out("save.async", "BufWritePre 里 async=true：磁盘上第 2 行缩进 " .. ws2 .. "（写的是格式化之前的内容），缓冲区 " .. s2 .. "，未保存的修改=" .. tostring(m2))

-- 五、旧接口
local c = vim.lsp.get_clients()[1]
local ok1, rc = pcall(function() return c.resolved_capabilities end)
local ok2, bf = pcall(function() return vim.lsp.buf.formatting end)
out("old.api", "client.resolved_capabilities = " .. (ok1 and tostring(rc) or "访问出错") .. "；vim.lsp.buf.formatting = " .. (ok2 and tostring(bf) or "访问出错") .. "；server_capabilities.documentFormattingProvider = " .. tostring(c.server_capabilities.documentFormattingProvider))
out("env", "nvim " .. tostring(vim.version()))
for _, cl in ipairs(vim.lsp.get_clients()) do cl:stop(true) end
vim.cmd("qa!")
