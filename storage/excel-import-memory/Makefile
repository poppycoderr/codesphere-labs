.PHONY: verify evidence clean
verify:    ## 生成 100 万行 xlsx 并测量两种读取方式的内存（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:
	rm -rf build target
