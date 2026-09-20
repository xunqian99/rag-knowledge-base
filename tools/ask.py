"""批量提问,用于验证检索精度。

用 Python 而不是 curl,是因为要发中文 JSON ——
Windows 命令行向 curl 传中文参数时编码经常出问题,Python 直接按 UTF-8 编码最稳。
"""

import json
import urllib.request

QUESTIONS = [
    # 干扰项 A:支付超时 30s vs 退款超时 60s
    "退款接口的超时时间是多少?",
    "创建支付接口的超时时间是多少?",
    # 干扰项 B:错误码 3001 vs 3002
    "错误码 3002 是什么意思?",
    # 干扰项 C:测试 3 实例 vs 生产 12 实例
    "生产环境有多少个实例?",
    # 干扰项 D:两个名字很像的服务
    "pay-gateway 的负责人是谁?",
    # 跨文档:答案在数据库说明里,但新人指南也提到了
    "t_payment_log 和 t_payment 有什么区别?",
    # 精确匹配
    "商户密钥多久轮换一次?",
    # 不可回答
    "公司的年终奖怎么算?",
]


def ask(question: str) -> dict:
    payload = json.dumps({"question": question, "sessionId": None}).encode("utf-8")
    request = urllib.request.Request(
        "http://localhost:8080/api/chat",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.loads(response.read().decode("utf-8"))


for index, question in enumerate(QUESTIONS, 1):
    try:
        result = ask(question)
        files = "、".join(sorted({c["fileName"] for c in result.get("citations", [])})) or "无"
        flags = []
        if result.get("degraded"):
            flags.append("降级")
        print(f"[{index}] 问:{question}")
        print(f"    答:{result['answer']}")
        print(f"    来源:{files}  耗时:{result['costMs']}ms {' '.join(flags)}")
    except Exception as exc:  # noqa: BLE001
        print(f"[{index}] 问:{question}")
        print(f"    失败:{exc}")
    print()
