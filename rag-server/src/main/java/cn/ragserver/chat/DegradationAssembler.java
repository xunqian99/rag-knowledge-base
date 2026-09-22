package cn.ragserver.chat;

import cn.ragserver.common.DegradationCode;
import cn.ragserver.retrieval.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把链路上散落的降级信息汇总成响应里的降级清单。
 *
 * 【为什么要单独抽一个类】
 *
 * 非流式和流式两条链路都要产出这份清单。如果各写一份,
 * 迟早会出现「同一个问题,普通接口说降级了、流式接口说没降级」这种自相矛盾的响应 ——
 * 和引用溯源那里共用 CitationBuilder 是同一个理由。
 *
 * 【为什么要逐个列出来,而不是合并成一句话】
 *
 * 一次请求可能同时踩中多处降级:比如 ES 挂了、精排也失败了。
 * 合并成一句话就没法区分"只是排序差一点"和"这压根不是答案"。
 */
@Component
public class DegradationAssembler {

    /**
     * @param retrieval        检索结果,携带召回通道的失败情况和精排降级标记
     * @param chatModelFailed  大模型是否失败并降级为返回原文
     */
    public List<AnswerResponse.Degradation> from(RetrievalResult retrieval, boolean chatModelFailed) {
        List<AnswerResponse.Degradation> degradations = new ArrayList<>(3);

        // 顺序刻意按"影响从大到小"排:大模型降级最严重,其次是召回范围缩小,最后是排序质量。
        // 前端如果要只挑一条展示,取第一条就是最严重的那个。
        if (chatModelFailed) {
            degradations.add(new AnswerResponse.Degradation(
                    DegradationCode.CHAT_MODEL_UNAVAILABLE.name(),
                    "大模型暂时不可用,以下是检索到的相关资料原文"));
        }
        if (retrieval.recallDegraded()) {
            degradations.add(new AnswerResponse.Degradation(
                    DegradationCode.RECALL_CHANNEL_DOWN.name(),
                    "部分召回通道不可用(" + String.join("、", retrieval.failedRecallChannels())
                            + "),本次只用了其余的召回结果"));
        }
        if (retrieval.rerankDegraded()) {
            degradations.add(new AnswerResponse.Degradation(
                    DegradationCode.RERANK_UNAVAILABLE.name(),
                    "重排序服务不可用,本结果未经精排,排序质量可能下降"));
        }
        return List.copyOf(degradations);
    }
}
