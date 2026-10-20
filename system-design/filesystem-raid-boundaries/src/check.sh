#!/usr/bin/env bash
set -euo pipefail
mkdir -p /tmp/ext4-demo
cd /tmp/ext4-demo
truncate -s 64M disk.img
mkfs.ext4 -q -F -b 4096 -U 00000000-0000-0000-0000-000000000001 -E lazy_itable_init=0,lazy_journal_init=0 disk.img
awk 'BEGIN {for(i=0;i<2048;i++) printf "record-%04d:example-data\n",i}' >payload.txt
debugfs -w -R 'write payload.txt /record' disk.img 2>&1
printf '\n--- inode and extent mapping ---\n'
debugfs -R 'stat /record' disk.img 2>&1
printf '\n--- metadata header ---\n'
dumpe2fs -h disk.img 2>/dev/null | sed -n '/Filesystem features:/p;/Block size:/p;/Inode size:/p'
debugfs -R 'dump /record restored.txt' disk.img 2>&1
cmp payload.txt restored.txt
cp disk.img snapshot.img
debugfs -w -R 'rm /record' disk.img 2>&1
printf '\n--- deletion in active image ---\n'
debugfs -R 'stat /record' disk.img 2>&1
printf '\n--- independent earlier image ---\n'
debugfs -R 'dump /record snapshot-record.txt' snapshot.img 2>&1
cmp payload.txt snapshot-record.txt
printf '\nroundtrip=true snapshot_roundtrip=true\n'
printf '\n--- packages ---\n'
dpkg-query -W e2fsprogs libext2fs2t64 libcom-err2 libss2 logsave coreutils
