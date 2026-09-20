package cn.ragserver.chat;

import cn.ragserver.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 大模型不可用时的降级答案。
 *
 * 把检索到的内容按编号列出来 —— 用户拿到的不是答案,而是一份"相关内容清单",
 * 需要自己读一遍。体验确实差,但比一个错误页有价值得多:
 * 检索那部分已经成功了,只是最后一步生成失败了,没道理把前面的工作全丢掉。
 *
 * 带 [1][2] 编号是有意的:这样引用解析逻辑完全不用改,
 * **降级路径和正常路径的 citations 结构保持一致**,前端也不需要特殊处理。
 */
@Component
public class FallbackAnswerBuilder {

    private static final String HEADER = "模型服务暂时不可用,以下是检索到的相关资料:\n\n";

    public String build(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append('[').append(i + 1).append("] 来源:").append(chunk.fileName()).append('\n')
                    .append(chunk.content()).append("\n\n");
        }
        return sb.toString();
    }
}
