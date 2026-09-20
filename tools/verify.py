"""端到端验证脚本。

一条命令跑完五组检查,直接给出 PASS / FAIL。用途有两个:
  1. 每次重启环境后确认系统是好的,而不是等到用的时候才发现有问题
  2. 改动代码之后做回归 —— 这几项一旦挂了,说明基础链路断了

用 Python 而不是 curl,是因为要发中文 JSON ——
Windows 命令行向原生程序传中文参数时编码经常出问题。
"""

import json
import sys
import time
import urllib.request

BASE = "http://localhost:8080"

results: list[tuple[bool, str, str]] = []


def record(ok: bool, name: str, detail: str = "") -> None:
    results.append((ok, name, detail))
    mark = "PASS" if ok else "FAIL"
    suffix = f"  —  {detail}" if detail else ""
    print(f"  [{mark}] {name}{suffix}")


def call(path: str, payload: dict | None = None, timeout: int = 120) -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    request = urllib.request.Request(BASE + path, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read().decode("utf-8")
        return json.loads(body) if body else {}


def ask(question: str) -> tuple[str, int]:
    started = time.time()
    result = call("/api/chat", {"question": question, "sessionId": None})
    elapsed = int((time.time() - started) * 1000)
    return result.get("answer", ""), result.get("costMs", elapsed)


print("\n=== 1. 后端与依赖 ===")
try:
    health = call("/api/system/health", timeout=30)
    down = [d["name"] for d in health.get("dependencies", []) if d["status"] != "UP"]
    detail = f"{len(health.get('dependencies', []))} 项全部 UP" if not down else f"未就绪:{down}"
    record(health.get("status") == "UP", "所有依赖健康", detail)
except Exception as exc:  # noqa: BLE001
    record(False, "所有依赖健康", f"请求失败:{exc}")

print("\n=== 2. 知识库内容 ===")
try:
    page = call("/api/documents?size=100")
    documents = page.get("items", [])
    chunks = sum(d["chunkCount"] for d in documents)
    indexed = [d for d in documents if d["status"] == "INDEXED"]
    record(len(documents) > 0, "文档已导入", f"{len(documents)} 份文档,{chunks} 个分块")
    record(len(indexed) == len(documents), "全部索引完成",
           f"{len(indexed)}/{len(documents)} 份状态为 INDEXED")
except Exception as exc:  # noqa: BLE001
    record(False, "文档已导入", f"请求失败:{exc}")

print("\n=== 3. 检索精度(故意埋的干扰项)===")
expectations = [
    ("退款接口的超时时间是多少?", "60", "不能答成支付接口的 30 秒"),
    ("创建支付接口的超时时间是多少?", "30", "不能答成退款的 60 秒"),
    ("错误码 3002 是什么意思?", "额度超限", "不能答成 3001 的余额不足"),
    ("商户密钥多久轮换一次?", "180", "精确数字"),
    ("pay-gateway 的负责人是谁?", "陈立", "从 Excel 里取"),
    ("t_payment_log 和 t_payment 有什么区别?", "日志", "区分只差后缀的两张表"),
]
for question, expected, note in expectations:
    try:
        answer, _ = ask(question)
        ok = expected in answer
        record(ok, question, note if ok else f"期望包含「{expected}」,实际:{answer[:60]}")
    except Exception as exc:  # noqa: BLE001
        record(False, question, f"请求失败:{exc}")

print("\n=== 4. 防幻觉(知识库里没有的问题)===")
try:
    answer, _ = ask("公司的年终奖是怎么计算的?")
    ok = "没有找到" in answer or "未找到" in answer
    record(ok, "无答案时如实说不知道", "没有硬编答案" if ok else f"实际:{answer[:80]}")
except Exception as exc:  # noqa: BLE001
    record(False, "无答案时如实说不知道", f"请求失败:{exc}")

print("\n=== 5. 缓存 ===")
try:
    question = "出差餐补是多少?"
    _, first = ask(question)
    _, second = ask(question)
    record(second < 500, "重复提问命中缓存", f"第一次 {first}ms -> 第二次 {second}ms")
except Exception as exc:  # noqa: BLE001
    record(False, "重复提问命中缓存", f"请求失败:{exc}")

passed = sum(1 for ok, _, _ in results if ok)
total = len(results)
print()
print("=" * 52)
print(f"  结果:{passed}/{total} 项通过")
if passed < total:
    print("  未通过:")
    for ok, name, detail in results:
        if not ok:
            print(f"    - {name}: {detail}")
print("=" * 52)
print()

sys.exit(0 if passed == total else 1)
