.PHONY: verify evidence clean
verify:    ## 完整验证并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除容器与构建产物
	docker compose -p cs-zsetcursor -f compose.yaml down -v --remove-orphans
	rm -rf build
