# 验证记录：Vim 的配置与插件怎样加载

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

| 场景 | 结果 |
|---|---|
| `runtimepath` | ~/.vim、pack 的 start 包、系统目录……最后是各个 after 目录 |
| 启动时的加载顺序 | vimrc → plugin/a.vim → pack/my/start/foo → after/plugin/a.vim |
| 三处依次给 `g:who` 赋值 | 最终是 after/plugin/a.vim 的值 |
| 启动后 autoload 函数 | 未定义，autoload/util.vim 未加载 |
| 调用 `util#hello()` | 此时才加载 autoload/util.vim |
| `:packadd bar` | 加载 opt 包 |
| ~/.vim/ftplugin/python.vim 设 `shiftwidth=3` | 打开 .py 后是 4，最后由系统的 ftplugin/python.vim 设置 |
| 再加 ~/.vim/after/ftplugin/python.vim 设 `shiftwidth=2` | 2 |
| `--noplugin` 启动 | 只加载 vimrc |

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 系统 python ftplugin 设置 `shiftwidth=4` 来自 Vim 自带运行时文件的推荐风格（`g:python_recommended_style`），其他文件类型的默认值不同。
- Debian 的软件包在 runtimepath 里加入了 `/var/lib/vim/addons` 和 `/etc/vim`，其他发行版或自行编译的 Vim 路径不同。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-17 | 首次建立，脚本断言全部通过 | 新文章，结论取自本次证据 |
