.PHONY: verify evidence clean
verify:    ## 完整验证并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/（全量备份与 binlog 文件本身只留在 build/work，证据中保存清单与 SHA-256）
	scripts/verify.sh evidence
clean:     ## 删除本实验的容器与数据卷
	docker compose -f compose.yaml down -v --remove-orphans
	rm -rf build
