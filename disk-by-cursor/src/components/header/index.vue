<template>
  <div class="pan-header-content">
    <div class="pan-header-content-wrapper">
      <div class="pan-title-font-content">
        <span class="pan-title-font" @click="goHome">卡码网盘</span>
      </div>
      <div class="pan-upload-task-list-content">
        <pan-task-list />
      </div>
      <div class="pan-user-info-content">
        <pan-user-info />
      </div>
    </div>
  </div>
</template>

<script setup>
// 导入自定义用户信息弹窗组件，pan-项目统一前缀
import PanUserInfo from '@/components/user-info/index.vue'
// 导入任务列表组件（上传/下载任务进度弹窗）
import PanTaskList from '@/components/task-list/index.vue'

// 引入vue-router组合式API，用于编程式路由跳转
import { useRouter } from 'vue-router'

// 获取路由实例对象
const router = useRouter()

/**
 * 跳转首页方法
 */
const goHome = () => {
  // 编程式导航，路由跳转至根路径首页
  router.push('/')
}

</script>

<style scoped>
.pan-header-content {
  top: 0;
  left: 0;
  width: 100%;
  z-index: 41;
  position: fixed;
}

.pan-header-content .pan-header-content-wrapper {
  height: 62px;
  display: flex;
  align-items: center;
  position: relative;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  transition: all 0.3s ease;
  padding: 0 20px;
}

.pan-header-content .pan-header-content-wrapper .pan-title-font-content {
  flex: 1;
}

.pan-header-content-wrapper .pan-title-font-content .pan-title-font {
  font-size: 24px;
  font-weight: 600;
  cursor: pointer;
  color: #fff;
  letter-spacing: 1px;
  transition: all 0.3s ease;
}

.pan-header-content-wrapper .pan-title-font-content .pan-title-font:hover {
  transform: scale(1.02);
}

.pan-header-content-wrapper .pan-user-info-content {
  margin-left: auto;
}

.pan-header-content-wrapper .pan-upload-task-list-content {
  margin-right: 20px;
}
</style>

