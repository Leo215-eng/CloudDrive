import { defineStore } from 'pinia'
import { ref, computed, nextTick } from 'vue'
import Cookies from 'js-cookie'
import { getUserInfo } from '@/api/user'
import { login, register } from '@/api/auth'

export const useUserStore = defineStore('user', () => {
  // 意思是：从浏览器 Cookie 里读取名叫 token 的值，如果没有就给空字符串，并用 Vue 的 ref 包成响应式变量。
  // 用户登录成功后，后端会返回一个 token，前端通常会把它保存到 Cookie、localStorage 或 Pinia 里。
  // 以后页面刷新时，内存里的数据会丢，但 Cookie 还在，
  // 所以这句代码可以重新把 token 读出来，判断用户是否已经登录，或者后续请求接口时继续带上这个 token。
  const token = ref(Cookies.get('token') || '')
  const userInfo = ref({})
  const rootFileId = ref('')
  const isLoggedIn = computed(() => !!token.value)

  const setToken = (newToken) => {
    token.value = newToken || ''
    if (token.value) {
      Cookies.set('token', token.value, { expires: 7 })
    } else {
      Cookies.remove('token')
    }
  }

  const clearToken = () => {
    setToken('')
    userInfo.value = {}
    rootFileId.value = ''
  }

  /**
   * 登录业务统一处理方法
   * @param {Object} loginData 登录表单参数（邮箱/密码/验证码等登录信息）
   * @returns {Object} 登录结果对象，包含success标识与提示文案
   */
  const loginAction = async (loginData) => {
    try {
      // 1. 调用后端登录接口，传入登录参数，等待接口返回结果
      const response = await login(loginData)

      // 2. 判断接口返回状态码，登录业务失败直接返回失败信息
      // 可选链?. 防止response为空时报错
      if (!response?.success) {
        return {
          success: false,
          message: response?.message || '登录失败'
        }
      }

      // 3. 从返回体中取出后端下发的JWT令牌
      const tokenValue = response.data
      // 校验token是否存在且为字符串类型，非法token直接拦截
      if (!tokenValue || typeof tokenValue !== 'string') {
        return {
          success: false,
          message: '登录返回token无效'
        }
      }
      // 4. 将合法token持久化存储（localStorage/cookie等）
      setToken(tokenValue)
      // 等待DOM更新完成，保证token存储生效后再执行后续操作
      await nextTick()
      // 5. 根据token拉取当前登录用户完整信息，存入全局状态
      await getUserInfoAction()

      // 全部流程执行完毕，返回登录成功标识
      return { success: true }
    } catch (error) {
      // 捕获接口请求、代码执行过程中所有异常（网络错误、接口500等）
      return {
        success: false,
        message: error.message || '登录失败'
      }
    }
  }


  const registerAction = async (registerData) => {
    try {
      const response = await register(registerData)
      if (response?.success) {
        return { success: true, data: response.data }
      }
      return { success: false, message: response?.message || '注册失败' }
    } catch (error) {
      return { success: false, message: error.message || '注册失败' }
    }
  }

// 获取当前登录用户信息的异步方法
  const getUserInfoAction = async () => {
    try {
      // 调用后端接口，拉取用户基础信息
      const response = await getUserInfo()
      // 判断接口返回失败，直接终止后续逻辑
      if (!response?.success) {
        return
      }

      // 将接口返回的用户信息赋值给页面响应式变量
      userInfo.value = response.data || {}
      // 如果用户存在根目录ID，同步赋值给全局根文件ID变量
      if (userInfo.value.rootFileId) {
        rootFileId.value = userInfo.value.rootFileId
      }
    } catch (_) {
      // 请求异常不做任何处理，静默捕获
      // no-op = no operation 无操作
    }
  }


  const initUserInfo = async () => {
    if (token.value) {
      await getUserInfoAction()
    }
  }

  const logout = () => {
    clearToken()
  }

  const updateAvatar = (avatarUrl) => {
    userInfo.value.avatar = avatarUrl
  }

  return {
    token,
    userInfo,
    rootFileId,
    isLoggedIn,
    loginAction,
    registerAction,
    getUserInfoAction,
    initUserInfo,
    logout,
    updateAvatar
  }
})
