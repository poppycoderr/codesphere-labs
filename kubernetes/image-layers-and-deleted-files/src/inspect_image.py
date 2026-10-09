"""读取 docker save 导出的镜像包，逐层报告：层里新增或修改了哪些文件、有哪些删除标记（whiteout）、哪些层的内容里能找到给定的字符串。只用标准库。"""
import io
import json
import sys
import tarfile


def main(path, needle):
    needle = needle.encode()
    with tarfile.open(path) as outer:
        manifest = json.load(outer.extractfile("manifest.json"))[0]
        config = json.load(outer.extractfile(manifest["Config"]))
        own = []  # 基础镜像之后、由 Dockerfile 指令产生的层
        base_layers = int(sys.argv[3]) if len(sys.argv) > 3 else 1
        for i, name in enumerate(manifest["Layers"]):
            data = outer.extractfile(name).read()
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as layer:
                files, whiteouts, found, size = [], [], False, 0
                for m in layer.getmembers():
                    base = m.name.rsplit("/", 1)[-1]
                    if base.startswith(".wh."):
                        whiteouts.append(m.name.replace(".wh.", ""))
                    elif m.isfile():
                        size += m.size
                        if i >= base_layers:
                            files.append(m.name)
                        if needle in layer.extractfile(m).read():
                            found = True
                if i >= base_layers:
                    own.append((i, sorted(files), sorted(whiteouts), found, size))
    print("layers\t共 %d 层，其中基础镜像 %d 层" % (len(manifest["Layers"]), base_layers))
    for i, files, whiteouts, found, size in own:
        print("layer_%d\t文件 %s\t删除标记 %s\t内容约 %d MB\t含有令牌 = %s" % (i, files or "无", whiteouts or "无", round(size / 1048576), str(found).lower()))
    history = json.dumps(config.get("history", []))
    print("config\t镜像配置的构建历史里含有令牌 = %s" % str(needle.decode() in history).lower())


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
