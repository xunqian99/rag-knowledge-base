<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Activity, CircleAlert, CircleCheck, LoaderCircle, Zap } from '@lucide/vue'
import { api } from '../api/client'
import type { HealthReport } from '../api/types'

const health = ref<HealthReport | null>(null)
const checking = ref(false)
const verifying = ref(false)
const verifyResult = ref<Record<string, string> | null>(null)

async function check() {
  checking.value = true
  try {
    health.value = await api.health()
  } catch {
    health.value = null
  } finally {
    checking.value = false
  }
}

/**
 * 深度探测:真的去调一次每个外部服务。
 *
 * 和上面的健康检查是两回事 ——
 * 健康检查只验证"密钥配了没有"(高频、零成本),
 * 深度探测验证"接口通不通"(手动触发、会消耗 API 额度)。
 *
 * 这个区分本身就是个设计取舍:如果把深度探测塞进健康检查里,
 * 监控系统每隔几秒轮询一次就会持续烧钱,而且外部接口慢会拖长响应。
 */
async function deepVerify() {
  verifying.value = true
  verifyResult.value = null
  try {
    verifyResult.value = await api.verify()
  } catch (error) {
    verifyResult.value = {
      探测失败: error instanceof Error ? error.message : '未知错误',
    }
  } finally {
    verifying.value = false
  }
}

function shortName(name: string) {
  if (name.includes('千帆')) return '千帆'
  if (name.includes('重排序')) return '精排'
  return name
}

onMounted(check)
</script>

<template>
  <header class="relative z-10 border-b border-ink-200/80 px-4 py-3">
    <div class="flex flex-wrap items-center gap-x-4 gap-y-3">
      <!-- 品牌区 -->
      <div class="flex items-center gap-3">
        <div
          class="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-accent-500 to-accent-600 shadow-lg shadow-accent-600/25"
        >
          <Zap class="size-4.5 text-white" />
        </div>
        <div>
          <h1 class="text-sm font-semibold tracking-tight text-ink-900">RAG 知识库问答</h1>
          <p class="text-[11px] text-ink-400">
            混合检索 · RRF 融合 · Cross-encoder 精排 · SSE 流式
          </p>
        </div>
      </div>

      <div class="ml-auto flex flex-wrap items-center gap-2">
        <!-- 依赖状态小徽标 -->
        <div class="flex flex-wrap items-center gap-1.5">
          <span
            v-for="dep in health?.dependencies ?? []"
            :key="dep.name"
            class="inline-flex items-center gap-1 rounded-full px-2 py-1 text-[11px] ring-1 transition-colors"
            :class="
              dep.status === 'UP'
                ? 'bg-emerald-500/8 text-emerald-600/90 ring-emerald-500/20'
                : 'bg-rose-500/10 text-rose-600 ring-rose-500/25'
            "
            :title="dep.error ?? `${dep.name} ${dep.status} (${dep.costMs}ms)`"
          >
            <span
              class="size-1.5 rounded-full"
              :class="dep.status === 'UP' ? 'bg-emerald-500' : 'bg-rose-500'"
            />
            {{ shortName(dep.name) }}
          </span>

          <span v-if="!health" class="text-[11px] text-ink-400">状态未知</span>
        </div>

        <button class="btn btn-ghost !px-2.5 !py-1.5 text-[11px]" :disabled="checking" @click="check">
          <component :is="checking ? LoaderCircle : Activity" class="size-3.5" :class="{ 'animate-spin': checking }" />
          刷新
        </button>
        <button
          class="btn btn-ghost !px-2.5 !py-1.5 text-[11px]"
          :disabled="verifying"
          title="真的调用一次 embedding / 精排 / 对话接口 —— 会消耗 API 额度"
          @click="deepVerify"
        >
          <component :is="verifying ? LoaderCircle : CircleCheck" class="size-3.5" :class="{ 'animate-spin': verifying }" />
          深度探测
        </button>
      </div>
    </div>

    <!-- 深度探测结果 -->
    <div v-if="verifyResult" class="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
      <span class="text-ink-400">连通性探测:</span>
      <span
        v-for="(value, key) in verifyResult"
        :key="key"
        class="inline-flex items-center gap-1 font-mono"
        :class="value.startsWith('OK') ? 'text-emerald-600' : 'text-rose-600'"
      >
        <component :is="value.startsWith('OK') ? CircleCheck : CircleAlert" class="size-3" />
        {{ key }} → {{ value }}
      </span>
    </div>
  </header>
</template>
