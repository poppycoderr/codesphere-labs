.PHONY: verify evidence clean
verify:    ## 初稿与修订模型各跑一遍验收测试 + 可追踪检查（输出到 target/run）
	scripts/verify.sh target/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	mvn -B -q clean
