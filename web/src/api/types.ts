/** 与后端 DTO 一一对应的类型定义 */

export type DocumentStatus = 'PENDING' | 'INDEXING' | 'INDEXED' | 'FAILED'

export interface DocumentResponse {
  id: number
  fileName: string
  fileType: string
  fileSize: number
  status: DocumentStatus
  chunkCount: number
  errorMessage: string | null
  createdAt: string
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export interface Citation {
  index: number
  chunkId: number
  documentId: number
  fileName: string
  chunkIndex: number
  score: number
  snippet: string
}

export interface AnswerResponse {
  sessionId: number
  answer: string
  citations: Citation[]
  costMs: number
  /** true 表示本次结果经过了降级(精排或大模型不可用) */
  degraded: boolean
}

export interface DependencyStatus {
  name: string
  status: 'UP' | 'DOWN'
  costMs: number
  error: string | null
}

export interface HealthReport {
  status: 'UP' | 'DOWN'
  checkedAt: string
  costMs: number
  dependencies: DependencyStatus[]
}

export interface ApiError {
  code: string
  message: string
  timestamp: string
}

/** 流式问答过程中回调收到的事件 */
export interface StreamHandlers {
  /** 检索阶段完成(拿到检索耗时与命中数) */
  onMeta?: (meta: { retrievalMs?: number; ttftMs?: number; hitCount?: number }) => void
  /** 收到一段增量文本 */
  onDelta?: (text: string) => void
  /** 全部完成 */
  onDone?: (result: AnswerResponse & { cached?: boolean; ttftMs?: number; totalMs?: number }) => void
  /** 出错 */
  onError?: (message: string) => void
}
