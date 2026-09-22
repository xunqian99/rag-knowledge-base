import { ref } from 'vue'
import { api } from '../api/client'
import type { Citation, Degradation } from '../api/types'

export interface ChatMessageMeta {
  retrievalMs?: number
  ttftMs?: number
  totalMs?: number
  cached?: boolean
  degraded?: boolean
  /** 降级清单。渲染成一个个标记,而不是笼统的"已降级" */
  degradations?: Degradation[]
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations: Citation[]
  streaming: boolean
  meta: ChatMessageMeta
  error?: string
}

let seq = 0
const nextId = () => `m${++seq}`

export function useChat() {
  const messages = ref<ChatMessage[]>([])
  const sessionId = ref<number | null>(null)
  const sending = ref(false)
  let controller: AbortController | null = null

  async function send(question: string) {
    const text = question.trim()
    if (!text || sending.value) return

    messages.value.push({
      id: nextId(),
      role: 'user',
      content: text,
      citations: [],
      streaming: false,
      meta: {},
    })

    messages.value.push({
      id: nextId(),
      role: 'assistant',
      content: '',
      citations: [],
      streaming: true,
      meta: {},
    })

    /*
     * 【这里有个 Vue 的经典陷阱,踩过才知道】
     *
     * 不能把上面那个字面量对象先存到一个局部变量里,然后去改它的属性。
     * 因为 push 进响应式数组的是「原始对象」,而模板里渲染的是
     * Vue 在读取数组元素时生成的「响应式代理」—— 两者不是同一个东西。
     *
     * 直接改原始对象会绕过代理的 setter,数据确实变了,但**不会触发重新渲染**,
     * 界面看起来就像卡住了。表现出来就是:流式输出一直没有内容。
     *
     * 正确做法是每次通过数组下标访问,拿到代理再改。
     */
    const replyIndex = messages.value.length - 1

    sending.value = true
    controller = new AbortController()

    try {
      await api.askStream(
        text,
        sessionId.value,
        {
          onMeta: (meta) => {
            Object.assign(messages.value[replyIndex].meta, meta)
          },
          onDelta: (chunk) => {
            messages.value[replyIndex].content += chunk
          },
          onDone: (result) => {
            const message = messages.value[replyIndex]
            message.content = result.answer || message.content
            message.citations = result.citations ?? []
            message.streaming = false
            message.meta = {
              ...message.meta,
              totalMs: result.totalMs,
              ttftMs: result.ttftMs,
              cached: result.cached,
              degraded: result.degraded,
              degradations: result.degradations ?? [],
            }
            if (result.sessionId != null) {
              sessionId.value = result.sessionId
            }
          },
          onError: (message) => {
            const target = messages.value[replyIndex]
            target.error = message
            target.streaming = false
          },
        },
        controller.signal,
      )
    } catch (error) {
      const target = messages.value[replyIndex]
      if (error instanceof DOMException && error.name === 'AbortError') {
        target.content = target.content || '(已停止生成)'
      } else {
        target.error = error instanceof Error ? error.message : '请求失败'
      }
      target.streaming = false
    } finally {
      sending.value = false
      controller = null
    }
  }

  function stop() {
    controller?.abort()
  }

  function reset() {
    controller?.abort()
    messages.value = []
    sessionId.value = null
  }

  return { messages, sending, send, stop, reset }
}
