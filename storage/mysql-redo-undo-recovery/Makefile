.PHONY: verify evidence clean
verify:    ## 运行并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除本实验的容器与数据卷
	docker compose -f compose.yaml down -v --remove-orphans
	rm -rf build
