.PHONY: verify evidence clean
verify:    ## 编译、测试并断言关键结论（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	rm -rf build
