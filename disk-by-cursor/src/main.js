// 引入Vue创建应用核心方法
import { createApp } from 'vue'
// 引入Pinia状态管理库
import { createPinia } from 'pinia'
// 引入Element Plus组件库
import ElementPlus from 'element-plus'
// 引入Element Plus全局样式
import 'element-plus/dist/index.css'
// 引入Element Plus全部图标
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
// 根组件
import App from './App.vue'
// 路由实例
import router from './router'
// 自定义全局主题样式
import './styles/theme.css'
// 用户状态仓库
import { useUserStore } from '@/stores/user'

// 创建Vue应用实例
const app = createApp(App)
// 创建Pinia实例
const pinia = createPinia()
// 全局注册Pinia
app.use(pinia)

// 循环批量注册所有Element Plus图标，页面可直接使用<图标名 />
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 获取用户仓库实例
const userStore = useUserStore()

// 异步初始化函数：先加载本地用户登录态，再挂载页面，防止路由守卫闪跳
async function bootstrap() {
  // 从本地缓存读取token、用户信息，恢复登录状态
  await userStore.initUserInfo()
  // 注册路由
  app.use(router)
  // 注册Element Plus组件库
  app.use(ElementPlus)
  // 将Vue应用挂载到页面#app节点，正式启动项目
  app.mount('#app')
}

// 执行初始化启动流程
bootstrap()
