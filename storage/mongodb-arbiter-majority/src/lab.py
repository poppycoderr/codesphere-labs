"""仲裁节点不存数据：PSA 副本集在一个数据节点停掉之后，w:majority 写入超时但已写入主节点；默认写关注是 w:1；
主节点切换后，只写到旧主节点的数据被回滚；从节点停机时改为 votes:0、priority:0 后写确认门槛从 2 降为 1。宿主机在阶段之间停止和启动容器，本脚本按阶段运行。输出 TSV（键、说明）。"""
import copy
import importlib.metadata
import sys
import time
from pathlib import Path

from bson import json_util
from pymongo import MongoClient
from pymongo.errors import OperationFailure, WriteConcernError, WTimeoutError
from pymongo.read_concern import ReadConcern
from pymongo.write_concern import WriteConcern


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def node(host, **kw):
    return MongoClient(host, 27017, directConnection=True, serverSelectionTimeoutMS=5000, **kw)


def wait(pred, what, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if pred():
                return
        except Exception:
            pass
        time.sleep(0.5)
    raise SystemExit(f"等待超时：{what}")


def primary_of(host):
    st = node(host).admin.command("replSetGetStatus")
    return next((m["name"].split(":")[0] for m in st["members"] if m["stateStr"] == "PRIMARY"), None)


def ids(client, rc="local"):
    coll = client.labs.get_collection("orders", read_concern=ReadConcern(rc))
    return sorted(d["_id"] for d in coll.find({}, {"_id": 1}).max_time_ms(10000))


phase = sys.argv[1]
output_dir = Path(sys.argv[2])

def archive(name, value):
    (output_dir / (name + '.json')).write_text(json_util.dumps(value, indent=2) + '\n')


if phase == "1":
    versions = {"python": sys.version.split()[0], "pymongo": importlib.metadata.version("pymongo"),
                "dnspython": importlib.metadata.version("dnspython")}
    assert versions == {"python": "3.14.8", "pymongo": "4.19.0", "dnspython": "2.9.0"}
    archive("client-versions", versions)
    c = node("m1")
    initial = {"_id": "rs0", "settings": {"electionTimeoutMillis": 4000},
               "members": [{"_id": 0, "host": "m1:27017"}, {"_id": 1, "host": "m2:27017"},
                           {"_id": 2, "host": "arb:27017", "arbiterOnly": True}]}
    # 9.0.4 要求选举超时大于心跳间隔（默认 2000 毫秒），旧配置的 2000 会被拒绝
    invalid = copy.deepcopy(initial)
    invalid["settings"]["electionTimeoutMillis"] = 2000
    try:
        c.admin.command("replSetInitiate", invalid)
    except OperationFailure as error:
        assert error.code == 93 and "heartbeatIntervalMillis" in str(error)
        archive("invalid-election-timeout", error.details)
    else:
        raise AssertionError("expected electionTimeoutMillis=heartbeatIntervalMillis to be rejected")
    c.admin.command("replSetInitiate", initial)
    wait(lambda: node("m1").admin.command("hello")["isWritablePrimary"], "m1 成为可写的主节点")
    p = node("m1")
    assert p.server_info()["version"] == "9.0.4"
    out("env", f"mongodb={p.server_info()['version']}")
    cfg = p.admin.command("replSetGetConfig")["config"]
    votes = sum(m["votes"] for m in cfg["members"]); data = sum(1 for m in cfg["members"] if not m.get("arbiterOnly"))
    out("config", f"投票成员 {votes} 个，存数据的成员 {data} 个，多数为 {votes // 2 + 1}")
    d = p.admin.command("getDefaultRWConcern")
    out("default_wc", f"getDefaultRWConcern：defaultWriteConcern 字段{'为 ' + str(d['defaultWriteConcern']) if 'defaultWriteConcern' in d else '不存在'}，来源 {d.get('defaultWriteConcernSource')}（隐式默认值由拓扑决定，下面用行为验证）")
    coll = p.labs.get_collection("orders", write_concern=WriteConcern("majority", wtimeout=3000))
    coll.insert_one({"_id": "a"})
    out("both_up", "两个数据节点都在：w:majority 写入 a 成功")

elif phase == "2":
    p = node("m1")
    out("m2_down.primary", f"停掉 m2 之后，主节点仍是 {primary_of('m1')}（m1 与 arb 两票构成多数）")
    coll = p.labs.get_collection("orders", write_concern=WriteConcern("majority", wtimeout=3000))
    try:
        coll.insert_one({"_id": "b"})
        out("m2_down.majority", "w:majority 写入 b：成功")
    except (WriteConcernError, WTimeoutError) as e:
        out("m2_down.majority", f"w:majority（wtimeout=3000）写入 b：{type(e).__name__}，错误码 {e.code}")
    out("m2_down.read", f"主节点上 readConcern local 读到 {ids(p)}；readConcern majority 读到 {ids(p, 'majority')}")
    p.labs.orders.insert_one({"_id": "c"})
    out("m2_down.default", "用默认写关注写入 c：立即确认，没有任何错误")

elif phase == "3":
    wait(lambda: node("m2").admin.command("hello")["isWritablePrimary"], "m2 成为可写的主节点")
    p = node("m2")
    out("failover.primary", f"停掉 m1、启动 m2 之后，m2 与 arb 两票选出主节点 {primary_of('m2')}")
    out("failover.read", f"新主节点上的数据：{ids(p)}（b、c 只在 m1 上）")
    p.labs.orders.insert_one({"_id": "d"})
    out("failover.write", "在新主节点上写入 d")

elif phase == "4":
    def m1_caught_up():
        st = node("m2").admin.command("replSetGetStatus")
        return any(m["name"].startswith("m1") and m["stateStr"] == "SECONDARY" for m in st["members"]) and \
            ids(node("m1", readPreference="secondaryPreferred")) == ["a", "d"]
    wait(m1_caught_up, "m1 作为从节点追上")
    out("rejoin.state", f"m1 重新加入后的状态：从节点；主节点 {primary_of('m2')}")
    m1 = node("m1", readPreference="secondaryPreferred")
    out("rejoin.data", f"m1 上的数据：{ids(m1)}；m2 上的数据：{ids(node('m2'))}；b、c 都不在了")

elif phase == "5":
    p = node("m2")
    wait(lambda: p.admin.command("hello")["isWritablePrimary"], "m2 保持主节点")
    before = p.admin.command("replSetGetStatus")
    config = p.admin.command("replSetGetConfig")["config"]
    archive("nonvoting-before-status", before)
    archive("nonvoting-before-config", config)
    assert before["writeMajorityCount"] == 2
    assert before["majorityVoteCount"] == 2 and before["votingMembersCount"] == 3
    target = next(m for m in config["members"] if m["host"] == "m1:27017")
    assert target["votes"] == 1 and not target.get("arbiterOnly", False)
    majority = p.labs.get_collection("orders", write_concern=WriteConcern("majority", wtimeout=3000))
    try:
        majority.insert_one({"_id": "e"})
    except (WriteConcernError, WTimeoutError) as error:
        assert error.code == 64
        archive("nonvoting-before-write-error", error.details)
    else:
        raise AssertionError("停机从节点仍有投票权时必须出现写关注超时")
    assert ids(p) == ["a", "d", "e"]
    out("nonvoting.before", "m1 停机仍有投票权：writeMajorityCount=2，majorityVoteCount=2，写入 e 超时（错误码64），local 已读到 e")
    target["votes"] = 0
    target["priority"] = 0
    config["version"] += 1
    result = p.admin.command({"replSetReconfig": config, "maxTimeMS": 15000})
    archive("nonvoting-reconfig-result", result)
    wait(lambda: p.admin.command("replSetGetStatus")["writeMajorityCount"] == 1, "写入确认门槛变为1")
    after = p.admin.command("replSetGetStatus")
    installed = p.admin.command("replSetGetConfig")["config"]
    archive("nonvoting-after-status", after)
    archive("nonvoting-after-config", installed)
    assert after["majorityVoteCount"] == 2 and after["votingMembersCount"] == 2
    assert after["writableVotingMembersCount"] == 1
    actual = next(m for m in installed["members"] if m["host"] == "m1:27017")
    assert (actual["votes"], actual["priority"]) == (0, 0)
    writes = []
    for document_id in ("f1", "f2", "f3"):
        result = majority.insert_one({"_id": document_id})
        assert result.acknowledged and result.inserted_id == document_id
        writes.append({"_id": result.inserted_id, "acknowledged": result.acknowledged})
    archive("nonvoting-writes", writes)
    observed = ids(p, "majority")
    assert observed == ["a", "d", "e", "f1", "f2", "f3"]
    archive("nonvoting-majority-read", observed)
    summary = {"mongodb": p.server_info()["version"], "force_reconfig": False,
               "secondary": "m1", "secondary_running_during_writes": False,
               "votes": actual["votes"], "priority": actual["priority"],
               "before_write_majority_count": before["writeMajorityCount"],
               "after_write_majority_count": after["writeMajorityCount"],
               "before_election_majority_count": before["majorityVoteCount"],
               "after_election_majority_count": after["majorityVoteCount"],
               "after_voting_members_count": after["votingMembersCount"],
               "after_writable_voting_members_count": after["writableVotingMembersCount"],
               "successful_majority_writes": len(writes), "majority_read": observed}
    archive("nonvoting-summary", summary)
    out("nonvoting.after", "m1 votes=0/priority=0 且仍停机：writeMajorityCount=1，majorityVoteCount=2；3次 w:majority（wtimeout=3000）写入均确认")
    out("nonvoting.read", f"调整后的 majority 读到 {observed}；此前超时的 e 也已进入提交视图")
