import axios from 'axios'
import { attachInterceptors } from '@/utils/attachInterceptors'
// axios.create()：创建一个自定义配置的 axios 实例（而非使用全局 axios），好处是不同业务模块（如认证、商品、订单）可独立配置请求规则，互不干扰。
//     baseURL: '/api/v1/auth'：设置该实例的请求基础路径，后续所有通过 authRequest 发起的请求，URL 都会自动拼接这个前缀（比如 /login 会变成 /api/v1/auth/login）。
//     timeout: 10000：设置请求超时时间为 10 秒，超过 10 秒未响应则终止请求并抛出超时错误。
const authRequest = axios.create({
  baseURL: '/api/v1/auth',
  timeout: 10000
})
attachInterceptors(authRequest, 'authRequest')

// 用户登录
export function login(data) {
  return authRequest({
    url: '/login',
    method: 'post',
    data
  })
}

// 用户注册
export function register(data) {
  return authRequest({
    url: '/register',
    method: 'post',
    data
  })
}
// 用户登出
export function logout() {
  return authRequest({
    url: '/logout',
    method: 'post'
  })
}