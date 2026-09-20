package cn.ragserver.chat;

/**
 * 提问请求。
 *
 * 类名没叫 ChatRequest,是因为 LangChain4j 里已有同名类
 * (dev.langchain4j.model.chat.request.ChatRequest),避免看起来像是同一个东西。
 *
 * sessionId 可以为空:为空表示开一段新会话,不为空则续在已有会话后面。
 */
public record QuestionRequest(String question, Long sessionId) {
}
