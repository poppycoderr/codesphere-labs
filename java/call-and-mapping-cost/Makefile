.PHONY: verify evidence clean
verify:    ## 构建、运行 JMH 与兼容性检查并断言（输出到 target/run）
	scripts/verify.sh target/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	rm -rf target
