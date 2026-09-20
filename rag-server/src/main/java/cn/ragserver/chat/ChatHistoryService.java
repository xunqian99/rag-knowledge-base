package cn.ragserver.chat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问答记录的持久化。
 *
 * 单独一个 Bean 的理由和 DocumentIndexService 一样:
 * Transactional 靠 AOP 代理生效,类内部自调用不经过代理,注解会静默失效。
 *
 * 另一个考虑:对话接口要调外部 API,可能耗时好几秒。
 * 如果把整个 ask 流程包在一个事务里,数据库连接会被长时间占用,
 * 并发一高连接池就爆了。所以只把「写库」这一小段放进事务。
 */
@Service
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public ChatHistoryService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 记录一轮问答。
     *
     * @param sessionId 为 null 表示新建会话
     * @return 会话 ID
     */
    @Transactional
    public Long record(Long sessionId, String question, String answer,
                       Long[] citedChunkIds, int latencyMs) {
        ChatSession session = sessionId == null
                ? null
                : sessionRepository.findById(sessionId).orElse(null);

        if (session == null) {
            session = new ChatSession();
            // 用问题开头当标题,会话列表里就能看出这段在聊什么。
            // 没有让大模型生成标题 —— 那要多花一次调用,不值当。
            session.setTitle(truncate(question, 40));
            session = sessionRepository.saveAndFlush(session);
        }

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(session.getId());
        userMessage.setRole(ChatMessage.ROLE_USER);
        userMessage.setContent(question);
        messageRepository.save(userMessage);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(session.getId());
        assistantMessage.setRole(ChatMessage.ROLE_ASSISTANT);
        assistantMessage.setContent(answer);
        assistantMessage.setCitedChunkIds(citedChunkIds);
        assistantMessage.setLatencyMs(latencyMs);
        messageRepository.save(assistantMessage);

        return session.getId();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
