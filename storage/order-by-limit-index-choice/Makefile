.PHONY: setup verify evidence clean
setup:     ## 启动容器并造数
	scripts/setup.sh && scripts/seed.sh
verify:    ## 完整验证并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除本实验的容器与数据卷
	scripts/cleanup.sh
	rm -rf build
