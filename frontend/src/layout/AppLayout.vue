<script setup lang="ts">
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'
</script>

<template>
  <el-container class="app-layout">
    <AppSidebar />
    <el-container class="app-main-container">
      <AppHeader />
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-layout {
  height: 100vh;
}

.app-main-container {
  flex-direction: column;
}

.app-main {
  background: var(--el-bg-color-page);
  padding: 20px;
  overflow-y: auto;
}
</style>
