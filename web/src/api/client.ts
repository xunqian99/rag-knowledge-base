import type {
  AnswerResponse,
  ApiError,
  DocumentResponse,
  HealthReport,
  PageResponse,
  StreamHandlers,
} from './types'

/**
 * 统一的请求封装。
 *
 * 后端所有错误都是 { code, message, timestamp } 的结构,
 * 这里把 HTTP 错误统一转成一个带中文提示的 Error 抛出去 ——
 * 调用方只需要 try/catch,不用关心状态码。
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)

  if (!response.ok) {
    let message = `请求失败(${response.status})`
    try {
      const error = (await response.json()) as ApiError
      if (error?.message) {
        message = error.message
      }
    } catch {
      // 响应体不是 JSON(比如网关返回的 HTML 错误页),就用默认提示
    }
    throw new Error(message)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const api = {
  /** 分页列出文档 */
  listDocuments(page = 0, size = 50) {
    return request<PageResponse<DocumentResponse>>(`/api/documents?page=${page}&size=${size}`)
  },

  /** 上传文档 */
  uploadDocument(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<DocumentResponse>('/api/documents', { method: 'POST', body: form })
  },

  /** 删除文档 */
  deleteDocument(id: number) {
    return request<void>(`/api/documents/${id}`, { method: 'DELETE' })
  },

  /** 按当前分块策略重新索引 */
  reindexDocument(id: number) {
    return request<DocumentResponse>(`/api/documents/${id}/reindex`, { method: 'POST' })
  },

  /** 依赖自检(只检查配置,不消耗 API 额度) */
  health() {
    return request<HealthReport>('/api/system/health')
  },

  /** 真实连通性探测(会真的调用外部 API) */
  verify() {
    return request<Record<string, string>>('/api/system/verify', { method: 'POST' })
  },

  /** 非流式提问 */
  ask(question: string, sessionId: number | null) {
    return request<AnswerResponse>('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, sessionId }),
    })
  },

  /**
   * 流式提问。
   *
   * 为什么不用浏览器原生的 EventSource:
   * **它只支持 GET,不能发 POST**,而我们的提问要带 JSON 请求体。
   * 所以只能回到 fetch,手动解析 ReadableStream 里的 SSE 帧。
   *
   * SSE 的帧格式是「event: 名字」换行「data: 内容」,帧之间用空行分隔。
   */
  async askStream(
    question: string,
    sessionId: number | null,
    handlers: StreamHandlers,
    signal?: AbortSignal,
  ): Promise<void> {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, sessionId }),
      signal,
    })

    if (!response.ok || !response.body) {
      let message = `请求失败(${response.status})`
      try {
        const error = (await response.json()) as ApiError
        if (error?.message) message = error.message
      } catch {
        /* 忽略 */
      }
      throw new Error(message)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    const dispatch = (frame: string) => {
      let eventName = 'message'
      const dataLines: string[] = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trim())
        }
      }
      if (dataLines.length === 0) return

      let payload: any
      try {
        payload = JSON.parse(dataLines.join('\n'))
      } catch {
        return
      }

      if (eventName === 'meta') handlers.onMeta?.(payload)
      else if (eventName === 'delta') handlers.onDelta?.(payload.text ?? '')
      else if (eventName === 'done') handlers.onDone?.(payload)
      else if (eventName === 'error') handlers.onError?.(payload.message ?? '未知错误')
    }

    // 逐块读取。必须自己攒缓冲区 ——
    // 网络返回的分块边界和 SSE 帧的边界没有任何关系,
    // 一帧完全可能被切成两次 read,两次 read 也可能包含三帧。
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        dispatch(buffer.slice(0, boundary))
        buffer = buffer.slice(boundary + 2)
        boundary = buffer.indexOf('\n\n')
      }
    }

    if (buffer.trim()) dispatch(buffer)
  },
}
