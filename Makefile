# 根目录入口。单个实验：make verify EXP=design/notification-routing
SHELL := /bin/bash
EXP ?=
TIER ?= regular

.PHONY: help check list verify verify-tier evidence clean

help:
	@echo "make check                         目录约定、元数据、脚本语法、敏感信息扫描"
	@echo "make list [TIER=regular]           列出实验"
	@echo "make verify EXP=<专题/实验>         运行单个实验的核心验证"
	@echo "make verify-tier TIER=<层级>        依次运行某一层级的全部实验（regular、regular-docker、performance）"
	@echo "make evidence EXP=<专题/实验>       重新采集单个实验的 evidence/"
	@echo "make clean EXP=<专题/实验>          清理单个实验创建的资源"

check:
	python3 shared/scripts/check-structure.py
	@for f in $$(git ls-files '*.sh'); do bash -n "$$f" || exit 1; done; echo "Shell 语法检查通过"
	shared/scripts/scan-secrets.sh

list:
	@python3 shared/scripts/list-experiments.py $(TIER)

verify evidence clean:
	@test -n "$(EXP)" || { echo "请指定 EXP=<专题/实验>，可用 make list 查看"; exit 1; }
	$(MAKE) -C $(EXP) $@

verify-tier:
	@set -e; for e in $$(python3 shared/scripts/list-experiments.py $(TIER)); do \
	  echo "==> $$e"; $(MAKE) -C $$e verify; $(MAKE) -C $$e clean >/dev/null; done
