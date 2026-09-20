<script setup lang="ts">
import AppHeader from './components/AppHeader.vue'
import ChatPanel from './components/ChatPanel.vue'
import DocumentPanel from './components/DocumentPanel.vue'
</script>

<template>
  <!-- z-10 是为了盖住 body::before 那层背景光晕 -->
  <div class="relative z-10 flex h-full flex-col">
    <AppHeader />

    <!--
      两栏布局。
      左栏固定宽度 —— 文档列表是辅助信息,不需要占太多;
      右栏用 minmax(0, 1fr) 而不是 1fr,否则内部的长文本会把栅格撑破。
    -->
    <main class="grid min-h-0 flex-1 gap-4 p-4 lg:grid-cols-[21rem_minmax(0,1fr)]">
      <DocumentPanel class="min-h-0" />
      <ChatPanel class="min-h-0" />
    </main>
  </div>
</template>
