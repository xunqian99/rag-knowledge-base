<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { CircleAlert, Clock, Gauge, Quote, Sparkles, Zap } from '@lucide/vue'
import type { ChatMessage } from '../composables/useChat'
import { highlightWithin, renderMarkdown } from '../utils/markdown'

const props = defineProps<{ message: ChatMessage }>()

const bodyRef = ref<HTMLElement | null>(null)
const html = computed(() => renderMarkdown(props.message.content || ''))

// 流式输出时每收到一段就重新渲染,渲染完顺手给代码块补上高亮。
// 用 nextTick 是因为 Vue 还没把新的 HTML 挂到 DOM 上,这时候查 DOM 是查不到的。
watch(
  () => props.message.content,
  async () => {
    await nextTick()
    if (bodyRef.value) highlightWithin(bodyRef.value)
  },
  { immediate: true },
)

const isUser = computed(() => props.message.role === 'user')
const hasMetrics = computed(
  () => props.message.meta.ttftMs != null || props.message.meta.totalMs != null,
)

function ms(value?: number) {
  if (value == null) return '—'
  return value >= 1000 ? `${(value / 1000).toFixed(2)}s` : `${value}ms`
}
</script>

<template>
  <!-- 用户消息:右对齐的气泡,和助手的卡片有明显区分 -->
  <div v-if="isUser" class="rise-in flex justify-end">
    <div
      class="max-w-[82%] rounded-2xl rounded-br-md bg-accent-600/85 px-4 py-2.5 text-sm leading-relaxed text-white shadow-lg shadow-accent-600/20"
    >
      {{ message.content }}
    </div>
  </div>

  <!-- 助手消息:整块卡片,下方带引用来源和性能指标 -->
  <div v-else class="rise-in">
    <div class="panel overflow-hidden">
      <!-- 卡片头:标识 + 状态标记 -->
      <div class="flex items-center gap-2 border-b border-ink-200/70 px-4 py-2.5">
        <Sparkles class="size-3.5 text-accent-600" />
        <span class="text-xs font-medium text-ink-600">助手</span>

        <span
          v-if="message.meta.cached"
          class="ml-1 inline-flex items-center gap-1 rounded-full bg-cyan-500/10 px-2 py-0.5 text-[11px] text-cyan-700 ring-1 ring-cyan-500/25"
        >
          <Zap class="size-3" /> 缓存命中
        </span>

        <!--
          降级标记要显眼。
          用户有权知道自己看到的结果是"完整链路跑出来的"还是"降级产物" ——
          后端一路把这个标记传上来,前端就不能把它藏起来。
        -->
        <span
          v-if="message.meta.degraded"
          class="inline-flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-[11px] text-amber-600 ring-1 ring-amber-500/25"
        >
          <CircleAlert class="size-3" /> 结果已降级
        </span>
      </div>

      <div class="px-4 py-3.5">
        <!-- 答案正文。streaming 时挂上光标,让用户看到"正在输出" -->
        <div
          ref="bodyRef"
          class="answer-body text-[13.5px] leading-[1.85] text-ink-900"
          :class="{ 'stream-cursor': message.streaming }"
          v-html="html"
        />

        <!-- 流式刚开始、正文还是空的时候给个占位,否则卡片会突然塌成一条 -->
        <div
          v-if="message.streaming && !message.content"
          class="flex items-center gap-2 text-xs text-ink-500"
        >
          <span class="size-1.5 animate-pulse rounded-full bg-accent-500" />
          正在检索并生成…
        </div>

        <p v-if="message.error" class="mt-2 text-xs text-rose-600">
          {{ message.error }}
        </p>
      </div>

      <!-- 引用来源 -->
      <div v-if="message.citations.length" class="border-t border-ink-200/70 px-4 py-3">
        <div class="mb-2 flex items-center gap-1.5 text-[11px] font-medium text-ink-500">
          <Quote class="size-3" />
          引用来源 · {{ message.citations.length }} 处
        </div>
        <div class="space-y-1.5">
          <div
            v-for="citation in message.citations"
            :key="citation.chunkId"
            class="group flex gap-2.5 rounded-lg border border-ink-200/60 bg-ink-100/40 px-2.5 py-2 transition-colors hover:border-ink-300 hover:bg-ink-100/50"
          >
            <span
              class="mt-px flex size-4.5 shrink-0 items-center justify-center rounded bg-accent-500/15 text-[10px] font-semibold text-accent-600"
            >
              {{ citation.index }}
            </span>
            <div class="min-w-0">
              <div class="flex items-center gap-2 text-[11px]">
                <span class="truncate font-medium text-ink-800">{{ citation.fileName }}</span>
                <span class="shrink-0 text-ink-400">第 {{ citation.chunkIndex }} 块</span>
                <span class="shrink-0 text-ink-400">相关度 {{ citation.score.toFixed(3) }}</span>
              </div>
              <p class="mt-0.5 line-clamp-2 text-[11px] leading-relaxed text-ink-500">
                {{ citation.snippet }}
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- 性能指标。首字延迟比总耗时更能反映用户的真实感受,所以放在前面 -->
      <div
        v-if="hasMetrics"
        class="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-ink-200/70 px-4 py-2 text-[11px] text-ink-400"
      >
        <span v-if="message.meta.retrievalMs != null" class="inline-flex items-center gap-1">
          <Gauge class="size-3" /> 检索 {{ ms(message.meta.retrievalMs) }}
        </span>
        <span v-if="message.meta.ttftMs != null" class="inline-flex items-center gap-1">
          <Clock class="size-3" /> 首字 {{ ms(message.meta.ttftMs) }}
        </span>
        <span v-if="message.meta.totalMs != null" class="inline-flex items-center gap-1">
          <Clock class="size-3" /> 总计 {{ ms(message.meta.totalMs) }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * Markdown 渲染出来的内容没法用 Tailwind 的工具类直接控制(v-html 生成的节点),
 * 所以这里用 :deep() 穿透 scoped 样式去修饰它。
 */
.answer-body :deep(p) {
  margin: 0 0 0.7em;
}
.answer-body :deep(p:last-child) {
  margin-bottom: 0;
}
.answer-body :deep(strong) {
  color: white;
  font-weight: 600;
}
.answer-body :deep(ul),
.answer-body :deep(ol) {
  margin: 0.4em 0 0.7em;
  padding-left: 1.25em;
}
.answer-body :deep(li) {
  margin: 0.2em 0;
}
.answer-body :deep(code) {
  background: var(--color-ink-800);
  border: 1px solid var(--color-ink-700);
  border-radius: 0.3em;
  padding: 0.1em 0.35em;
  font-size: 0.88em;
}
.answer-body :deep(pre) {
  background: var(--color-ink-900);
  border: 1px solid var(--color-ink-700);
  border-radius: 0.6rem;
  padding: 0.8em 1em;
  overflow-x: auto;
  margin: 0.6em 0;
}
.answer-body :deep(pre code) {
  background: transparent;
  border: none;
  padding: 0;
}
.answer-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0.6em 0;
  font-size: 0.92em;
}
.answer-body :deep(th),
.answer-body :deep(td) {
  border: 1px solid var(--color-ink-700);
  padding: 0.4em 0.6em;
  text-align: left;
}
.answer-body :deep(th) {
  background: var(--color-ink-800);
  color: var(--color-ink-200);
}
.answer-body :deep(a) {
  color: var(--color-accent-400);
  text-decoration: underline;
}
</style>
