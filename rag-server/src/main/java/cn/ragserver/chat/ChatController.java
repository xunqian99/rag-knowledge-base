package cn.ragserver.chat;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;

    public ChatController(ChatService chatService, ChatStreamService chatStreamService) {
        this.chatService = chatService;
        this.chatStreamService = chatStreamService;
    }

    /**
     * 提问。
     *
     * 请求体:{"question": "出差住宿能报多少?", "sessionId": null}
     * sessionId 传 null 表示新开一段会话。
     */
    @PostMapping
    public AnswerResponse ask(@RequestBody QuestionRequest request) {
        return chatService.ask(request);
    }

    /**
     * 流式提问。
     *
     * 返回 text/event-stream,事件序列是:
     *   event: meta   data: {"retrievalMs": 1820, "hitCount": 3}
     *   event: meta   data: {"ttftMs": 2350}
     *   event: delta  data: {"text": "出差"}
     *   event: delta  data: {"text": "住宿的"}
     *   ...
     *   event: done   data: {"sessionId":1, "answer":"...", "citations":[...],
     *                        "retrievalMs":1820, "ttftMs":2350, "totalMs":3900}
     *
     * 出错时发一个 error 事件,而不是把连接直接掐断 ——
     * 前端能拿到具体原因并展示给用户。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody QuestionRequest request) {
        return chatStreamService.stream(request);
    }
}
