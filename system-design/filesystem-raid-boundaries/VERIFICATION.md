# 离线 ext4 镜像的数据映射与时间副本：验证记录

## 一、结论

64MiB 普通镜像文件里的 51,200 字节文件分配为 13 个 4KiB 数据块，debugfs 显示 extent；导出后按字节一致。活动镜像删除后找不到路径，而先前独立复制的镜像仍能导出相同内容。

## 二、环境与数据

Debian trixie 固定 digest；e2fsprogs、libext2fs2t64、libcom-err2、libss2、logsave 均固定为 1.47.2-3+b12；coreutils 9.7-3。普通容器 network=none，cap-drop=ALL，no-new-privileges，1 CPU、256MiB。

## 三、步骤与断言

构建固定版本工具镜像；src/check.sh 只在容器临时目录创建镜像、写入、导出、复制、删除和再次导出。容器退出即清理临时文件，Docker 构建镜像供重跑保留。

## 四、证据

ext4.txt 保存 mkfs/debugfs/dumpe2fs 相关核心原始输出、按字节比较成功记录和包版本；environment.txt 为环境。

## 五、限制与不能推出的结论

没有 mount、loop 设备、真实磁盘或特权容器。没有运行 RAID、坏盘、重建或 HDFS；正文相关内容是文档依据与示意。独立复制未挂载镜像不等于在线一致性快照或异地备份。块位置和 inode 时间随创建环境变化，不作固定阈值。

## 六、复现与清理

`make verify` 运行并断言，结果进入 build/run；`make evidence` 重新运行并采集 evidence；`make clean` 删除本实验的构建文件。容器均随结束清理；网络实验还会删除自己的 compose 网络。不自动删除可复用镜像或共享依赖缓存。

## 七、验证历史

- 2026-10-19：首次验证。先完成程序断言，再按观察写正文；未进行多机性能或生产验证。
- 2026-10-20：review 后补脚本头部说明；运行前清空输出目录。
