.PHONY: verify evidence clean
verify:    ## 完整验证并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除本实验的容器与数据卷
	bash -c 'source ../../shared/scripts/lib.sh && mysql_down csl-idempotency-key'
	rm -rf build
