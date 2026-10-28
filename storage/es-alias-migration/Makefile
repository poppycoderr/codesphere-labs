.PHONY: verify evidence clean
verify:    ## 完整验证并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除构建产物与容器
	docker compose -p csl-esalias -f compose.yaml down -v --remove-orphans
	rm -rf build
