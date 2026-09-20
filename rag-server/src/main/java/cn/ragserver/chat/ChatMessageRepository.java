package cn.ragserver.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 按会话取出历史消息,按时间正序。多轮对话(后续项)会用到。 */
    List<ChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);
}
