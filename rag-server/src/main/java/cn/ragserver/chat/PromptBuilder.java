package cn.ragserver.chat;

import cn.ragserver.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 组装。
 *
 * 单独抽出来是因为 prompt 是需要反复调的东西,
 * 而且它本身是「业务文案」而不是「技术逻辑」,混在 Service 里会越改越乱。
 */
@Component
public class PromptBuilder {

    /**
     * 系统指令。
     *
     * 【这是全项目投入产出比最高的一段文字】
     *
     * 第 2 条「资料里没有就直接说不知道」是抑制幻觉的核心手段。
     * 不加这一句,大模型会非常自然地用它自己的知识去补全答案 ——
     * 用户看到的是一段流畅自信、但和你的知识库毫无关系的回答,
     * 这比直接说「没找到」危险得多。
     *
     * 第 3 条要求标注来源编号,是为了让答案可核对。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个企业知识库问答助手。请严格依据下面提供的【参考资料】回答用户的问题。

            规则:
            1. 只使用【参考资料】中的信息作答,不要使用你自己的知识进行补充或推测。
            2. 如果【参考资料】中没有能够回答问题的内容,直接回复:资料中没有找到相关内容。
            3. 引用资料时在句末标注来源编号,例如 [1]、[2]。
            4. 用中文回答,简洁准确,不要整段复述资料原文。
            """;

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 用户消息:把检索到的资料按编号列出,再附上问题。
     *
     * 编号必须与返回给前端的 citations 一一对应,
     * 否则模型说「参考 [2]」、前端却高亮到另一条,引用溯源就失去意义了。
     */
    public String userPrompt(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("【参考资料】\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append('[').append(i + 1).append("] 来源:").append(chunk.fileName()).append('\n')
                    .append(chunk.content()).append("\n\n");
        }
        sb.append("【用户问题】\n").append(question);
        return sb.toString();
    }
}
