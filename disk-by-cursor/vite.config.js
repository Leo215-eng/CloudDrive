// 导入Vite的配置定义函数
import { defineConfig } from 'vite'
// 导入Vite的Vue插件，用于支持Vue单文件组件
import vue from '@vitejs/plugin-vue'
// 导入Node.js的路径处理模块，用于解析文件路径
import path from 'path'

// 导出Vite的配置对象，使用defineConfig辅助函数提供类型提示
export default defineConfig({
  // 配置Vite使用的插件，这里启用Vue插件
  plugins: [vue()],
  
  // 解析相关配置
  resolve: {
    // 配置路径别名，简化项目中文件导入的路径书写
    alias: {
      // 将@符号映射为项目根目录下的src文件夹路径
      '@': path.resolve(__dirname, 'src')
    }
  },
  
  // 开发服务器相关配置
  server: {
    // 配置代理规则，解决开发环境下的跨域问题
    proxy: {
      // 匹配/api/v1/auth开头的请求
      '/api/v1/auth': {
        // 目标服务器地址（认证服务）
        target: 'http://localhost:8090',
        // 开启跨域请求头修改（改变请求源）
        changeOrigin: true,
        // 路径重写规则：此处原样转发（正则匹配开头的/api/v1/auth，替换为相同路径）
        rewrite: path => path.replace(/^\/api\/v1\/auth/, '/api/v1/auth')
      },
      // 匹配/api/v1/files开头的请求（文件服务）
      '/api/v1/files': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/api\/v1\/files/, '/api/v1/files')
      },
      // 匹配/api/v1/shares开头的请求（分享服务）
      '/api/v1/shares': {
        target: 'http://localhost:8085',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/api\/v1\/shares/, '/api/v1/shares')
      },
      // 匹配/api/v1/users开头的请求（用户服务）
      '/api/v1/users': {
        target: 'http://localhost:8086',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/api\/v1\/users/, '/api/v1/users')
      },
      // 匹配/api/v1/recycles开头的请求（回收站服务）
      '/api/v1/recycles': {
        // 优先从环境变量读取目标地址，未定义则使用默认值
        target: process.env.VITE_RECYCLE_TARGET || 'http://localhost:8084',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/api\/v1\/recycles/, '/api/v1/recycles')
      },
      // 匹配/api/v1/ai开头的请求（AI服务）
      '/api/v1/ai': {
        target: 'http://localhost:8087',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/api\/v1\/ai/, '/api/v1/ai')
      }
    }
  }
}) 
