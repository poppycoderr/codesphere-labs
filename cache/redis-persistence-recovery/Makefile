.PHONY: verify evidence clean
verify:    ## 完整验证并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除本实验的容器（保留构建的镜像）
	docker compose -f compose.yaml down -v --remove-orphans
	rm -rf build
