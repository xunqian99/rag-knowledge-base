#!/bin/sh
#
# 分块参数对比实验:改一个参数 -> 按新参数重新索引全部文档 -> 评测一轮。
#
# 【为什么要在容器里跑】
# 容器里挂了 application-local.yml(含 API Key),又能用服务名直连
# postgres / elasticsearch / redis —— 是这套依赖里最完整的环境,
# 不用在宿主机上另外配一遍。
#
# 【怎么用】(在项目根目录)
#   docker exec rag-server mkdir -p /app/eval
#   docker cp eval/questions.jsonl rag-server:/app/eval/questions.jsonl
#   docker cp tools/chunking-experiment.sh rag-server:/app/chunking-experiment.sh
#   docker exec -d -w /app rag-server sh /app/chunking-experiment.sh
#
# 【结果在哪】
#   /app/eval/report-<max>.md     每种参数下的评测报告
#   /app/eval/count-<max>.json    每种参数下的分块数
#   /app/eval/progress.txt        进度标记,出现 "DONE restore" 表示全部跑完
#   /app/eval/run-<max>.log       完整运行日志
#
# 【运行时长】每一组大约 2 分钟评测 + 1.5 分钟拒答阈值校准,四组合计约 15 分钟。
# 校准那一节会跟着每组报告一起生成(report-<max>.md),所以顺带能看到
# 「不同分块粒度下,拒答阈值曲线长什么样」—— 它是按当前索引状态算的。
#
# 【重要】脚本最后会用 RESTORE_MAX 重新索引一次,把语料恢复到基线。
# 少了这一步,知识库会停在最后一组实验参数上,
# 和 README / eval/report.md 里记录的数字就对不上了。

set -u

ES=${ES_URI:-http://localhost:9200}
INDEX=rag-chunk-v1
EVAL_MAIN="cn.ragserver.eval.EvaluationMain"
LAUNCHER="org.springframework.boot.loader.launch.PropertiesLauncher"

# 基线值。和 application.yml 里的 rag.chunking.max-size 保持一致。
RESTORE_MAX=120

run_one() {
  m=$1
  tag=$2
  echo "[$(date +%H:%M:%S)] 开始 max-size=$m ($tag)" >> /app/eval/progress.txt
  java -Dloader.main=$EVAL_MAIN -cp app.jar $LAUNCHER \
       --eval.reindex=true \
       --eval.mode=HYBRID_RERANK \
       --rag.chunking.max-size=$m \
       > /app/eval/run-$tag.log 2>&1
  cp /app/eval/report.md /app/eval/report-$tag.md
  # 统计分块数之前必须先强制刷新。
  # ES 的 bulk 写入不是立刻可见的,不刷新直接 _count 会读到旧数据 ——
  # 第一次跑这个脚本时就踩到了:max=500 记成 91(其实是上一轮的残留),
  # max=120 记成 61(删除已生效、新增还没可见)。
  # 这个坑和决策记录 013 里记的是同一类问题。
  curl -s -XPOST "$ES/$INDEX/_refresh" > /dev/null
  curl -s "$ES/$INDEX/_count" > /app/eval/count-$tag.json
  echo "[$(date +%H:%M:%S)] DONE $tag" >> /app/eval/progress.txt
}

for M in 500 120 50; do
  run_one $M $M
done

# 恢复基线,避免实验把知识库留在非最优参数上
run_one $RESTORE_MAX restore

echo "[$(date +%H:%M:%S)] ALL DONE" >> /app/eval/progress.txt
