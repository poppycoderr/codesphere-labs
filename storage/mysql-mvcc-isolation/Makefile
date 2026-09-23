.PHONY: verify evidence clean
verify:    ## 运行并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	bash -c 'source ../../shared/scripts/lib.sh && mysql_down csl-mvcc-isolation'
	rm -rf build
