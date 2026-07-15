<template>
  <!-- 页面主体内容外层容器 -->
  <div class="pan-app-main-content">
    <!--<router-view> = 可变内容的空位
    路由匹配到哪个子页面，就把那个页面塞到这个空位里显示。-->
    <!-- :key="$route.fullPath"     只要地址变一点点（哪怕只是后面多了？type=image），key 就变了。
    Vue 看到 key 不一样，会把旧页面销毁，重新创建全新页面，自动重新拉取文件列表、刷新面包屑。-->
<!--    :key="$route.fullPath" 是为了让路由变化时强制重新渲染组件。-->
<!--    Vue Router 根据当前 URL 自动控制 router-view 渲染哪个组件-->
    <router-view :key="$route.fullPath" />
  </div>
</template>

<script setup>
// 网盘布局主体内容容器组件 PanAppMain
// 作用：承载路由匹配的页面（首页/文件列表/回收站/分享页等）
</script>


<style scoped>
.pan-app-main-content {
  width: 100%;
  height: 100%;
  padding: 8px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
  overflow: hidden;
  box-sizing: border-box;
  max-width: 100%;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .pan-app-main-content {
    padding: 6px;
  }
}

@media (max-width: 992px) {
  .pan-app-main-content {
    padding: 4px;
  }
}
</style>

