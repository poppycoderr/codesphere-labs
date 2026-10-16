.PHONY: verify evidence clean
verify:    ## 完整验证并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除构建产物与镜像
	rm -rf build
	docker rmi csl-vim:9.1.1230 >/dev/null 2>&1 || true
