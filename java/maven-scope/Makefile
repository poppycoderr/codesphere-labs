.PHONY: verify evidence clean
verify:    ## 验证并断言（输出到 target/run）
	scripts/verify.sh target/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	rm -rf target
