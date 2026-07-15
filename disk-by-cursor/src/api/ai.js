import axios from 'axios'
import { attachInterceptors } from '@/utils/attachInterceptors'

// AI 模块专用请求实例。
// baseURL=/api/v1/ai 表示下面所有 url 都会拼到这个前缀后面。
// 例如 url='/files/summary' 最终请求的是 /api/v1/ai/files/summary。
const aiRequest = axios.create({
  baseURL: '/api/v1/ai',
  timeout: 30000
})
// 给这个 axios 实例挂统一拦截器：自动带 token、处理未登录、统一拆后端 Result。
attachInterceptors(aiRequest, 'aiRequest')

// 查询 AI 服务能力：服务名、模型名、是否启用向量检索、支持哪些功能。
// 页面打开抽屉时会调用它，用来展示“openai-compatible / qwen / 向量检索已启用”等状态标签。
export function getAiCapabilities() {
  return aiRequest({
    url: '/capabilities',
    method: 'get'
  })
}

// 建立或重建当前文件的向量索引。
// 后端会读取文件 -> 解析文本 -> 切块 -> 生成 embedding -> 存入 PostgreSQL + pgvector。
export function indexAiFile(data) {
  return aiRequest({
    url: '/files/index',
    method: 'post',
    data
  })
}

// 生成文件摘要。
// data 里通常带 fileId、filename、prompt；prompt 为空时后端可复用已保存的默认摘要。
export function summarizeFile(data) {
  return aiRequest({
    url: '/files/summary',
    method: 'post',
    data
  })
}

// 生成智能标签。
// 后端会把文档内容交给模型，让模型返回短标签，再解析成数组返回前端。
export function generateFileTags(data) {
  return aiRequest({
    url: '/files/tags',
    method: 'post',
    data
  })
}

// 单文件问答。
// 后端优先用向量检索找相关文档片段，再把片段和问题一起交给模型回答。
export function askSingleFileQuestion(data) {
  return aiRequest({
    url: '/files/question',
    method: 'post',
    data
  })
}
