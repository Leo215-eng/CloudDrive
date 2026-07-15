<template>
<!--  <pan-header />、<pan-navbar />、<pan-app-main /> 全都是自己项目手写自定义组件-->
  <!-- 最外层容器：承载整个网盘后台布局 -->
  <div class="pan-content">
    <!-- 1. 顶部通栏组件 -->
    <pan-header />

    <!-- 侧边栏 + 主体内容 横向包裹容器 -->
    <div class="pan-main-wrapper">
      <!-- 左侧侧边栏容器 -->
      <!-- :class 动态绑定样式：isSidebarCollapsed=true 时追加 sidebar-collapsed 类，css压缩侧边栏宽度、隐藏菜单文字 -->

      <!--   : 是 v-bind 的简写，意思：把 JS 里的变量 / 表达式绑定给 HTML 属性。
        class 属性绑定后，类名不再写死，由 JS 逻辑控制加不加。-->

    <!--动态 :class="{ key: value }" 对象语法：
        key：类名字符串 'sidebar-collapsed'
        value：布尔变量 isSidebarCollapsed
        规则：
          isSidebarCollapsed.value = false → 不加 sidebar-collapsed
          isSidebarCollapsed.value = true → 自动追加 sidebar-collapsed-->

      <div class="sidebar-container" :class="{ 'sidebar-collapsed': isSidebarCollapsed }">
        <!-- 左侧导航菜单组件，把折叠状态传给子组件，菜单同步收缩文字 -->
        <pan-navbar :is-collapsed="isSidebarCollapsed" />

        <!-- 侧边栏收缩/展开切换按钮 -->
        <div class="sidebar-toggle" @click="toggleSidebar">
          <el-icon>
            <!-- 侧边栏展开：显示左箭头，点击收起 -->
            <ArrowLeft v-if="!isSidebarCollapsed" />
            <!-- 侧边栏收起：显示右箭头，点击展开 -->
            <ArrowRight v-else />
          </el-icon>
        </div>
      </div>

      <!-- 中间主体内容区域，路由子页面渲染出口 -->
      <pan-app-main />
    </div>
  </div>
</template>


<script setup>
// 导入Vue组合式API，ref创建布尔响应式变量
import { ref } from 'vue'
// 导入ElementPlus左右箭头图标，用于折叠按钮
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

// 导入三个全局布局子组件
// 渲染该组件内部所有 template 内容；
// 执行它内部的 script 逻辑（ref、方法、接口请求、状态）；
// 加载它内部的 style 样式。
import PanHeader from '@/components/header/index.vue'     // 顶部栏
import PanNavbar from '@/components/navbar/index.vue'     // 左侧菜单
import PanAppMain from '@/components/app-main/index.vue'  // 中间内容区

// 定义响应式变量：侧边栏是否折叠，默认false=展开状态
const isSidebarCollapsed = ref(false)

// 切换侧边栏展开/收起的方法
const toggleSidebar = () => {
  // ! 取反，true变false，false变true
  isSidebarCollapsed.value = !isSidebarCollapsed.value
}
</script>



<style scoped>
.pan-content {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8f0 100%);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  transition: all 0.3s ease;
}

.pan-main-wrapper {
  display: flex;
  flex: 1;
  margin-top: 62px;
  padding: 8px;
  gap: 8px;
  min-width: 0;
  position: relative;
  height: calc(100vh - 62px);
}

.sidebar-container {
  position: relative;
  transition: all 0.3s ease;
  flex-shrink: 0;
}

.sidebar-container.sidebar-collapsed {
  width: 50px;
}

.sidebar-container:not(.sidebar-collapsed) {
  width: 160px;
}

.sidebar-toggle {
  position: absolute;
  right: -12px;
  top: 50%;
  transform: translateY(-50%);
  width: 24px;
  height: 24px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 10;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  transition: all 0.3s ease;
}

.sidebar-toggle:hover {
  background: #f5f7fa;
  transform: translateY(-50%) scale(1.1);
}

.pan-main-wrapper > pan-app-main {
  flex: 1;
  min-width: 0;
  overflow: auto;
}

/* 响应式布局 */
@media (max-width: 1200px) {
  .pan-main-wrapper {
    gap: 8px;
    padding: 8px;
  }
}

@media (max-width: 992px) {
  .pan-main-wrapper {
    gap: 6px;
    padding: 6px;
  }
}
</style> 