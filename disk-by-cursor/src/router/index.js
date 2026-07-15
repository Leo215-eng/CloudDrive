// 导入Vue Router的核心方法：创建路由器和Web历史模式
import { createRouter, createWebHistory } from 'vue-router'
// 导入用户状态管理的Pinia Store
import { useUserStore } from '@/stores/user'
// 导入Pinia的辅助方法，用于将store中的属性转为响应式引用
import { storeToRefs } from 'pinia'

// 定义路由规则数组
const routes = [
  // 登录页路由
  {
    path: '/login', // 路由路径
    name: 'Login', // 路由名称（唯一标识）
    // 懒加载组件：访问该路由时才加载Login.vue，优化首屏加载速度
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false } // 路由元信息：该页面不需要登录即可访问
  },
  // 注册页路由
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { requiresAuth: false } // 不需要登录即可访问
  },
  // 分享访问页路由
  {
    path: '/share',
    name: 'ShareAccess',
    component: () => import('@/views/ShareAccess.vue'),
    meta: { requiresAuth: false } // 不需要登录即可访问
  },
  // 主布局路由（需要登录才能访问）
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/views/Layout.vue'),
    meta: { requiresAuth: true }, // 需要登录才能访问
    // 嵌套子路由：基于Layout.vue布局的子页面
    children: [
      // 首页（默认子路由，匹配/路径）
      {
        path: '', // 空路径表示父路由的默认子路由
        name: 'Home',
        component: () => import('@/views/Home.vue')
      },
      // 文件管理页
      {
        path: 'files', // 完整路径为 /files
        name: 'Files',
        component: () => import('@/views/Files.vue')
      },
      // 分享管理页
      {
        path: 'shares', // 完整路径为 /shares
        name: 'Shares',
        component: () => import('@/views/Shares.vue')
      },
      // 回收站页面
      {
        path: 'recycle', // 完整路径为 /recycle
        name: 'Recycle',
        component: () => import('@/views/Recycle.vue')
      }
    ]
  }
]

// 创建路由器实例
const router = createRouter({
  history: createWebHistory(), // 使用HTML5 History模式（无#的URL）
  routes // 传入定义好的路由规则
})

// 全局前置路由守卫：每次路由跳转前执行，用于权限控制
router.beforeEach((to, from, next) => {
  // 获取用户Store实例（Pinia）
  const userStore = useUserStore()
  // 解构出isLoggedIn并保持响应式（storeToRefs避免失去响应式）
  const { isLoggedIn } = storeToRefs(userStore)

  // 调试日志：打印路由跳转信息，方便开发排查问题
  console.log('路由守卫检查:', {
    to: to.path, // 目标路由路径
    from: from.path, // 来源路由路径
    isLoggedIn: isLoggedIn.value, // 当前登录状态
    requiresAuth: to.meta.requiresAuth // 目标路由是否需要登录
  })

  // 权限判断逻辑
  // 1. 目标路由需要登录，但用户未登录 → 跳转到登录页
  if (to.meta.requiresAuth && !isLoggedIn.value) {
    console.log('需要认证但未登录，跳转到登录页') // 补充缺失的console.log
    next('/login') // 跳转到登录页
  }
  // 2. 用户已登录，但访问登录/注册页 → 跳转到首页（防止重复登录）
  else if ((to.path === '/login' || to.path === '/register') && isLoggedIn.value) {
    console.log('已登录但访问登录/注册页，跳转到首页')
    next('/') // 跳转到首页
  }
  // 3. 其他情况（权限匹配）→ 正常放行
  else {
    console.log('路由检查通过')
    next() // 放行，进入目标路由
  }
})

// 导出路由器实例，供main.js引入使用
export default router 
