<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { Eraser, Send, Sparkles, Square } from '@lucide/vue'
import { useChat } from '../composables/useChat'
import MessageBubble from './MessageBubble.vue'

const { messages, sending, send, stop, reset } = useChat()

const draft = ref('')
const listRef = ref<HTMLElement | null>(null)

// 预置几个示范问题。
// 前两个是"文档里有答案"的(而且答案分布在多份文档里),
// 第三个是"文档里只有相关词、没有答案"的 —— 用来展示防幻觉:
// 文档里提到过年假,但没写多少天,系统应该说"没找到"而不是编一个。
const suggestions = [
  '出差住宿能报多少?',
  '自驾出差怎么补贴?',
  '客户招待能报多少?',
  '公司的年假一共有多少天?',
]

async function submit() {
  const text = draft.value.trim()
  if (!text) return
  draft.value = ''
  await send(text)
}

function onKeydown(event: KeyboardEvent) {
  // Enter 发送,Shift + Enter 换行。
  // isComposing 是为了照顾中文输入法 —— 拼音还没上屏时敲回车不应该触发发送。
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

// 内容变化就滚到底部。
// 依赖里带上最后一条消息的长度,这样流式输出时每收到一段都会跟着滚。
watch(
  () => messages.value.map((m) => m.content.length).join(','),
  async () => {
    await nextTick()
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
</script>

<template>
  <section class="panel flex min-h-0 flex-col">
    <header class="flex items-center justify-between border-b border-ink-200/70 px-4 py-2.5">
      <div class="flex items-center gap-2">
        <Sparkles class="size-4 text-accent-600" />
        <h2 class="text-sm font-medium text-ink-900">知识库问答</h2>
      </div>
      <button
        class="btn btn-ghost !px-2.5 !py-1.5 text-[11px]"
        :disabled="messages.length === 0"
        @click="reset"
      >
        <Eraser class="size-3.5" />
        清空
      </button>
    </header>

    <div ref="listRef" class="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-4">
      <!-- 空状态:给几个可以直接点的问题,降低"不知道该问什么"的门槛 -->
      <div
        v-if="messages.length === 0"
        class="flex h-full flex-col items-center justify-center gap-6 px-6 text-center"
      >
        <div
          class="flex size-14 items-center justify-center rounded-2xl bg-accent-500/10 ring-1 ring-accent-500/25"
        >
          <Sparkles class="size-6 text-accent-600" />
        </div>
        <div>
          <p class="text-sm text-ink-800">问点什么吧</p>
          <p class="mt-1 text-xs text-ink-400">
            答案会从已上传的文档里找依据,并附上引用来源
          </p>
        </div>
        <div class="flex flex-wrap justify-center gap-2">
          <button
            v-for="item in suggestions"
            :key="item"
            class="rounded-full border border-ink-200 bg-ink-100/60 px-3 py-1.5 text-xs text-ink-600 transition-colors hover:border-accent-500/40 hover:bg-accent-500/10 hover:text-accent-600"
            @click="send(item)"
          >
            {{ item }}
          </button>
        </div>
      </div>

      <MessageBubble v-for="message in messages" :key="message.id" :message="message" />
    </div>

    <footer class="border-t border-ink-200/70 p-3">
      <div
        class="flex items-end gap-2 rounded-xl border border-ink-200 bg-ink-100/70 p-2 transition-colors focus-within:border-accent-500/50"
      >
        <textarea
          v-model="draft"
          rows="1"
          placeholder="输入问题…"
          class="max-h-32 min-h-[2.25rem] flex-1 resize-none bg-transparent px-2 py-1.5 text-[13.5px] leading-relaxed text-ink-900 outline-none placeholder:text-ink-400"
          @keydown="onKeydown"
        />
        <button v-if="sending" class="btn btn-ghost !px-3" @click="stop">
          <Square class="size-3.5" />
          停止
        </button>
        <button v-else class="btn btn-primary !px-3" :disabled="!draft.trim()" @click="submit">
          <Send class="size-3.5" />
          发送
        </button>
      </div>
      <p class="mt-1.5 px-1 text-[11px] text-ink-400">
        Enter 发送,Shift + Enter 换行 — 答案下方会标注引用来源,可据此核对原文
      </p>
    </footer>
  </section>
</template>
