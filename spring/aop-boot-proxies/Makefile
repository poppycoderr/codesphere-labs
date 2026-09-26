.PHONY: verify evidence clean
verify:    ## mvn test + 断言（输出到 target/run）
	scripts/verify.sh target/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	mvn -B -q clean
