import contextlib, json, os
from pyspark.sql import SparkSession, functions as F
spark=(SparkSession.builder.master('local[2]').appName('execution-boundaries')
       .config('spark.sql.shuffle.partitions','4').config('spark.sql.adaptive.enabled','false')
       .config('spark.eventLog.enabled','true').config('spark.eventLog.compress','false')
       .config('spark.eventLog.dir','file:/out/events').config('spark.eventLog.rolling.enabled','false')
       .config('spark.ui.enabled','false').getOrCreate())
spark.sparkContext.setLogLevel('ERROR')
base=(spark.range(10000,numPartitions=4).select('id',F.when(F.col('id')<8000,F.lit(0)).otherwise((F.col('id')%100)+1).alias('key')))
narrow=base.filter('id % 2 = 0').select('id','key')
grouped=base.groupBy('key').count()
with open('/out/plans.txt','w') as f, contextlib.redirect_stdout(f):
    print('--- narrow ---');narrow.explain('formatted')
    print('--- aggregate ---');grouped.explain('formatted')
assert 'Exchange' not in narrow._jdf.queryExecution().executedPlan().toString()
assert 'Exchange' in grouped._jdf.queryExecution().executedPlan().toString()
assert not base.is_cached
counts={r['key']:r['count'] for r in grouped.collect()}
assert counts[0]==8000 and sum(counts.values())==10000
# Observe rows reaching partitions without a partial aggregate hiding the hot key.
plain=base.repartition(4,'key')
salted=base.withColumn('salt',(F.col('id')%16).cast('int')).repartition(4,'key','salt')
def sizes(df):return sorted(df.rdd.mapPartitions(lambda rows:[sum(1 for _ in rows)]).collect())
plain_sizes=sizes(plain);salted_sizes=sizes(salted)
assert max(plain_sizes)>=8000 and max(salted_sizes)<max(plain_sizes)
partial=salted.groupBy('key','salt').count()
merged=partial.groupBy('key').agg(F.sum('count').alias('count'))
assert {r['key']:r['count'] for r in merged.collect()}==counts
base.cache();base.count();assert base.is_cached
assert 'InMemoryTableScan' in base.filter('key=0')._jdf.queryExecution().executedPlan().toString()
with open('/out/cache-plan.txt','w') as f,contextlib.redirect_stdout(f):base.filter('key=0').explain('formatted')
base.unpersist();assert not base.is_cached
result={'spark':spark.version,'rows':10000,'hot_key_rows':8000,'plain_partition_rows':plain_sizes,
        'salted_partition_rows':salted_sizes,'salted_aggregation_equal':True,
        'uncached_by_default':True,'cache_after_materialization':True}
spark.stop()
# Event logs are retained whole; extract raw stage info and actual shuffle metrics.
stages=[]
for root,_,files in os.walk('/out/events'):
    for name in files:
        path=os.path.join(root,name)
        if name.startswith('.') or name.endswith('.crc'):continue
        try:
            with open(path) as f:
                for line in f:
                    e=json.loads(line)
                    if e.get('Event')=='SparkListenerStageCompleted':
                        info=e['Stage Info'];stages.append({'id':info['Stage ID'],'tasks':info['Number of Tasks'],'accumulables':info.get('Accumulables',[])})
                    if e.get('Event')=='SparkListenerTaskEnd':
                        m=e.get('Task Metrics',{});result.setdefault('shuffle_write_bytes',0)
                        result['shuffle_write_bytes']+=m.get('Shuffle Write Metrics',{}).get('Shuffle Bytes Written',0)
        except UnicodeDecodeError:continue
assert stages and result['shuffle_write_bytes']>0
with open('/out/stages.json','w') as f:json.dump(stages,f,indent=2)
with open('/out/results.json','w') as f:json.dump(result,f,indent=2)
print(json.dumps(result,indent=2))
