package cn.ragserver.cache;

import cn.ragserver.chat.AnswerResponse;

import java.util.List;

/**
 * 缓存的问答结果。
 *
 * 只存答案和引用,不存 sessionId —— 会话是每次请求各自的,
 * 缓存它是错的(第二个人命中同一条缓存,不该被塞进第一个人的会话里)。
 */
public record CachedAnswer(String answer, List<AnswerResponse.Citation> citations) {
}
