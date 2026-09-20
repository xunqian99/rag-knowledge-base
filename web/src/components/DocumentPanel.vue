<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  CircleAlert,
  CircleCheck,
  Database,
  FileText,
  LoaderCircle,
  RefreshCw,
  Trash,
  Upload,
} from '@lucide/vue'
import { api } from '../api/client'
import type { DocumentResponse } from '../api/types'
import { formatBytes } from '../utils/markdown'

const documents = ref<DocumentResponse[]>([])
const loading = ref(false)
const uploading = ref(false)
const dragging = ref(false)
const busyIds = ref<number[]>([])
const notice = ref<{ type: 'ok' | 'error'; text: string } | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

async function load() {
  loading.value = true
  try {
    const page = await api.listDocuments()
    documents.value = page.items
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载文档失败')
  } finally {
    loading.value = false
  }
}

function notify(type: 'ok' | 'error', text: string) {
  notice.value = { type, text }
  window.setTimeout(() => {
    if (notice.value?.text === text) notice.value = null
  }, 4000)
}

async function uploadFiles(files: FileList | null) {
  if (!files || files.length === 0) return
  uploading.value = true
  try {
    for (const file of Array.from(files)) {
      const result = await api.uploadDocument(file)
      if (result.status === 'INDEXED') {
        notify('ok', `${result.fileName} 已索引,共 ${result.chunkCount} 个分块`)
      } else {
        notify('error', `${result.fileName} 处理失败:${result.errorMessage ?? '未知原因'}`)
      }
    }
    await load()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '上传失败')
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

function onDrop(event: DragEvent) {
  dragging.value = false
  uploadFiles(event.dataTransfer?.files ?? null)
}

async function run(id: number, action: () => Promise<unknown>) {
  busyIds.value = [...busyIds.value, id]
  try {
    await action()
    await load()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '操作失败')
  } finally {
    busyIds.value = busyIds.value.filter((item) => item !== id)
  }
}

function remove(document: DocumentResponse) {
  // 删除是不可撤销的(后端用的是硬删除),所以必须问一次
  if (!window.confirm(`确定删除《${document.fileName}》?\n\n它的分块、向量和检索索引都会被一并清除。`)) {
    return
  }
  run(document.id, () => api.deleteDocument(document.id))
}

function reindex(document: DocumentResponse) {
  run(document.id, () => api.reindexDocument(document.id))
}

function statusMeta(status: DocumentResponse['status']) {
  switch (status) {
    case 'INDEXED':
      return { label: '已索引', cls: 'text-emerald-600 bg-emerald-500/10 ring-emerald-500/25' }
    case 'FAILED':
      return { label: '失败', cls: 'text-rose-600 bg-rose-500/10 ring-rose-500/25' }
    default:
      return { label: '处理中', cls: 'text-amber-600 bg-amber-500/10 ring-amber-500/25' }
  }
}

const totalChunks = () => documents.value.reduce((sum, item) => sum + item.chunkCount, 0)

onMounted(load)
defineExpose({ load })
</script>

<template>
  <section class="panel flex min-h-0 flex-col">
    <header class="flex items-center justify-between border-b border-ink-200/70 px-4 py-2.5">
      <div class="flex items-center gap-2">
        <Database class="size-4 text-accent-600" />
        <h2 class="text-sm font-medium text-ink-900">知识库</h2>
      </div>
      <div class="flex items-center gap-2">
        <span class="text-[11px] text-ink-400">
          {{ documents.length }} 份 · {{ totalChunks() }} 个分块
        </span>
        <button
          class="rounded-md p-1 text-ink-400 transition-colors hover:bg-ink-200 hover:text-ink-800 disabled:opacity-40"
          :disabled="loading"
          title="刷新列表"
          @click="load"
        >
          <RefreshCw class="size-3.5" :class="{ 'animate-spin': loading }" />
        </button>
      </div>
    </header>

    <div class="border-b border-ink-200/70 p-3">
      <!-- 拖拽上传区 -->
      <div
        class="flex cursor-pointer flex-col items-center gap-2 rounded-xl border border-dashed px-4 py-5 text-center transition-all"
        :class="
          dragging
            ? 'border-accent-500 bg-accent-500/10'
            : 'border-ink-300 hover:border-ink-300 hover:bg-ink-100/40'
        "
        @click="fileInput?.click()"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
      >
        <component
          :is="uploading ? LoaderCircle : Upload"
          class="size-5 text-ink-500"
          :class="{ 'animate-spin text-accent-600': uploading }"
        />
        <p class="text-xs text-ink-600">
          {{ uploading ? '正在上传并建立索引…' : '拖拽文件到这里,或点击选择' }}
        </p>
        <p class="text-[11px] text-ink-400">支持 txt / pdf / docx / xlsx,单文件不超过 50MB</p>
      </div>
      <input
        ref="fileInput"
        type="file"
        class="hidden"
        accept=".txt,.pdf,.docx,.xlsx"
        @change="uploadFiles(($event.target as HTMLInputElement).files)"
      />

      <p
        v-if="notice"
        class="mt-2 flex items-start gap-1.5 rounded-lg px-2.5 py-2 text-[11px] leading-relaxed"
        :class="
          notice.type === 'ok'
            ? 'bg-emerald-500/10 text-emerald-600'
            : 'bg-rose-500/10 text-rose-600'
        "
      >
        <component
          :is="notice.type === 'ok' ? CircleCheck : CircleAlert"
          class="mt-px size-3.5 shrink-0"
        />
        <span class="break-all">{{ notice.text }}</span>
      </p>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto p-2">
      <p v-if="loading && documents.length === 0" class="px-3 py-6 text-center text-xs text-ink-400">
        加载中…
      </p>
      <p
        v-else-if="documents.length === 0"
        class="px-3 py-6 text-center text-xs leading-relaxed text-ink-400"
      >
        还没有文档<br />
        上传一份试试
      </p>

      <div
        v-for="document in documents"
        v-else
        :key="document.id"
        class="group rounded-xl px-2.5 py-2.5 transition-colors hover:bg-ink-100/50"
      >
        <div class="flex items-start gap-2.5">
          <FileText class="mt-0.5 size-4 shrink-0 text-ink-400" />
          <div class="min-w-0 flex-1">
            <p class="truncate text-[13px] text-ink-900" :title="document.fileName">
              {{ document.fileName }}
            </p>
            <div class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-ink-400">
              <span
                class="rounded-full px-1.5 py-0.5 text-[10px] ring-1"
                :class="statusMeta(document.status).cls"
              >
                {{ statusMeta(document.status).label }}
              </span>
              <span>{{ formatBytes(document.fileSize) }}</span>
              <span>{{ document.chunkCount }} 块</span>
            </div>
            <p
              v-if="document.errorMessage"
              class="mt-1 line-clamp-2 text-[11px] leading-relaxed text-rose-600/90"
            >
              {{ document.errorMessage }}
            </p>
          </div>

          <!-- 操作按钮平时淡出,悬停才显现 —— 列表本身才是主角 -->
          <div
            class="flex shrink-0 gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100"
          >
            <button
              class="rounded-md p-1.5 text-ink-500 transition-colors hover:bg-ink-200 hover:text-accent-600 disabled:opacity-40"
              :disabled="busyIds.includes(document.id) || document.status !== 'INDEXED'"
              title="按当前分块策略重新索引"
              @click="reindex(document)"
            >
              <RefreshCw
                class="size-3.5"
                :class="{ 'animate-spin': busyIds.includes(document.id) }"
              />
            </button>
            <button
              class="rounded-md p-1.5 text-ink-500 transition-colors hover:bg-rose-500/15 hover:text-rose-600 disabled:opacity-40"
              :disabled="busyIds.includes(document.id)"
              title="删除"
              @click="remove(document)"
            >
              <Trash class="size-3.5" />
            </button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
