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

/**
 * 一条降级说明。
 *
 * code 给程序做分支判断(稳定标识),message 直接展示给人看。
 *
 * 为什么不是一个布尔值:这个项目里有两种性质完全不同的降级 ——
 * 精排失败时答案仍然是正常答案,只是排序退化了;
 * 大模型失败时返回的**根本不是答案**,而是一份检索到的原文清单。
 * 两者在界面上本该完全不同地展示,一个 true/false 区分不了。
 */
export interface Degradation {
  code: string
  message: string
}

export interface AnswerResponse {
  sessionId: number
  answer: string
  citations: Citation[]
  costMs: number
  /** true 表示本次结果经过了降级,等价于 degradations 非空 */
  degraded: boolean
  /** 本次踩到的降级清单。空数组表示完整链路跑完,没有任何环节降级 */
  degradations: Degradation[]
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
