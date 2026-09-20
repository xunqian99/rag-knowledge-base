// 用 lib/common 而不是默认入口:
// 默认入口会把 190 多种语言的语法定义全打进包里(约 700KB),
// lib/common 只带 30 多种常用语言,足够覆盖实际会遇到的代码块。
// 这是前端很典型的一个取舍 —— 打包体积和功能覆盖面的平衡。
import hljs from 'highlight.js/lib/common'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 知识库文档里常有表格(比如从 Excel 解析出来的报销标准),
// 所以 GFM 必须打开,否则表格会渲染成一行文本。
marked.setOptions({
  gfm: true,
  breaks: true,
})

/**
 * 把 Markdown 渲染成 HTML。
 *
 * 【最后一步的 DOMPurify 不是可选项】
 *
 * marked 默认**允许原始 HTML 通过**。而这里要渲染的内容是:
 * 用户上传的文档 → 大模型读过之后组织出来的答案。
 *
 * 也就是说,只要有人在文档里写一段 <img src=x onerror=...> 或者 <script>,
 * 模型完全可能把它原样复述出来,然后 v-html 就会在浏览器里执行它 ——
 * **这是一条从「上传文档」直达「任意代码执行」的路径。**
 *
 * 对 Markdown 渲染器做 XSS 清洗是基本操作,不能因为"这是内部系统"就省掉。
 */
export function renderMarkdown(text: string): string {
  const raw = marked.parse(text, { async: false }) as string
  return DOMPurify.sanitize(raw)
}

/**
 * 给已经渲染进 DOM 的代码块加高亮。
 *
 * 为什么不在 renderMarkdown 里做:
 * marked 的渲染器自定义 API 在各版本间变化过几次,直接在 DOM 上后处理更稳,
 * 而且和"什么时候渲染"完全解耦 —— 流式输出每收到一段就重渲染一次,
 * 后处理只需要管当前 DOM 里有没有没高亮过的块。
 */
export function highlightWithin(container: HTMLElement) {
  container.querySelectorAll<HTMLElement>('pre code').forEach((block) => {
    if (block.dataset.highlighted === 'yes') return
    hljs.highlightElement(block)
  })
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function formatTime(iso: string): string {
  const date = new Date(iso)
  return date.toLocaleString('zh-CN', { hour12: false })
}
