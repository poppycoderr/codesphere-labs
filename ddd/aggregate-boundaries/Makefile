.PHONY: verify evidence clean
verify:    ## 运行三组对照并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	bash -c 'source ../../shared/scripts/lib.sh && mysql_down csl-ddd-aggregate'
	rm -rf build
