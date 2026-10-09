.PHONY: verify evidence clean
verify:    ## 运行并断言（输出到 build/run）
	scripts/verify.sh build/run
evidence:  ## 重新采集 evidence/
	scripts/verify.sh evidence
clean:     ## 删除集群容器与数据卷
	docker compose -p csl-kafka-key-ordering down -v --remove-orphans
	rm -rf build
