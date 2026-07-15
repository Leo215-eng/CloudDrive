<template>
  <div class="register-container">
    <div class="register-box">
      <div class="register-header">
        <div class="logo">
          <!-- Element Plus 文件夹图标作为logo -->
          <el-icon class="logo-icon"><Folder /></el-icon>
        </div>
        <h2>用户注册</h2>
        <p>创建您的卡码网盘账号，开启云端存储之旅</p>
      </div>
      <!-- 注册表单核心组件：
              - ref="registerFormRef"：表单引用，用于调用表单校验等方法
              - :model="registerForm"：绑定表单数据源，表单输入值自动同步到该对象
              - :rules="registerRules"：绑定表单校验规则，Element Plus 内置 async-validator 校验引擎
              - @submit.prevent="handleRegister"：监听表单原生提交事件（回车触发），.prevent 阻止页面刷新，执行注册逻辑
            -->
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        class="register-form"
        @submit.prevent="handleRegister"
      >

        <!-- 邮箱输入项：
          - prop="email"：关联 registerRules 中的 email 校验规则，无此属性则不触发校验
          - el-input：Element Plus 输入框组件，v-model 双向绑定表单邮箱值
          - prefix-icon：输入框前置图标，提升视觉体验
          - size="large"：大尺寸输入框，适配移动端/PC端视觉
          - class="custom-input"：自定义样式类，覆盖默认样式
        -->
        <el-form-item prop="email">
          <el-input
            v-model="registerForm.email"
            placeholder="Email"
            prefix-icon="User"
            size="large"
            class="custom-input"
          />
        </el-form-item>
        <!-- 用户名输入项：
                  - prop="nickName"：关联 registerRules 中的 nickName 校验规则
                  - UserFilled 填充图标，区分邮箱/用户名视觉
                -->
        <el-form-item prop="nickName">
          <el-input
            v-model="registerForm.nickName"
            placeholder="Nickname"
            prefix-icon="UserFilled"
            size="large"
            class="custom-input"
          />
        </el-form-item>
        <!-- 密码输入项：
                  - type="password"：密码类型，输入内容隐藏
                  - show-password：显示密码切换按钮，提升用户体验
                  - Lock 锁形图标，暗示密码输入
                -->
        <el-form-item prop="password">
          <el-input
            v-model="registerForm.password"
            type="password"
            placeholder="请输入密码"
            prefix-icon="Lock"
            size="large"
            show-password
            class="custom-input"
          />
        </el-form-item>

        <el-form-item prop="confirmPassword">
          <el-input
            v-model="registerForm.confirmPassword"
            type="password"
            placeholder="Confirm password"
            prefix-icon="Lock"
            size="large"
            show-password
            class="custom-input"
          />
        </el-form-item>
<!--        :loading="loading" 动态绑定加载状态，loading.value 为 true 按钮自动转圈、不可点击-->
        <!-- 注册按钮项：
         - type="primary"：Element Plus 主色调按钮，突出核心操作
         - :loading="loading"：绑定加载状态，true 时按钮转圈且不可点击
         - @click="handleRegister"：点击触发注册逻辑（与表单回车提交逻辑一致）
         - 动态显示图标/文字：加载中显示“注册中...”，否则显示“注册”+图标
       -->
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            class="register-btn"
            :loading="loading"
            @click="handleRegister"
          >
            <el-icon v-if="!loading"><UserFilled /></el-icon>
            {{ loading ? '注册中...' : '注册' }}
          </el-button>
        </el-form-item>
<!--        $router.push('/login')：点击 “已有账号？立即登录” 时，跳转到登录页（Vue Router 的跳转方法）-->
        <div class="register-footer">
          <el-link type="primary" @click="$router.push('/login')" class="login-link">
            <el-icon><Right /></el-icon>
            已有账号？立即登录
          </el-link>
        </div>
      </el-form>
    </div>

    <div class="bg-decoration">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
      <div class="circle circle-3"></div>
    </div>
  </div>
</template>

<script setup>

// Vue3 核心：ref（基本类型响应式）、reactive（对象类型响应式）
import { ref, reactive } from 'vue'
// Vue Router：路由跳转工具，用于注册成功后跳转到登录页
import { useRouter } from 'vue-router'
// Element Plus：消息提示组件，用于注册成功/失败的用户反馈
import { ElMessage } from 'element-plus'
// Pinia 仓库：用户状态管理，封装了注册接口调用逻辑，解耦组件与接口
import { useUserStore } from '@/stores/user'
// 路由实例：用来跳转页面
const router = useRouter()
// Pinia仓库实例：用来调用注册接口
const userStore = useUserStore()

// 表单引用：用于调用 Element Plus 表单的校验方法（validate)
const registerFormRef = ref()
// 按钮加载状态，默认false不加载
const loading = ref(false)

// 表单数据：存储用户输入的邮箱、用户名、密码
// 初始时registerForm的所有字段都是空字符串，用户输入后会通过v-model自动填充。
const registerForm = reactive({
  email: '',
  nickName: '',
  password: '',
  confirmPassword: ''
})
// 用户输入 el-input
// → el-input 内部触发 change / input / blur 事件
// → el-form-item 收到这个字段发生了变化或失焦
// → el-form-item 通过 prop="email" 知道自己管 email 字段
// → 它去 el-form 的 model 中取 registerForm.email
// → 它去 el-form 的 rules 中找 registerRules.email
// → 按 trigger 判断哪些规则该执行
// → 执行 required/type/validator 等校验
// → 成功就不提示，失败就在表单项下面显示 message
const registerRules = {
  // 它像一张规则表，意思是：email 字段必填，失去焦点时检查；还要符合邮箱格式，失去焦点或内容变化时检查。
  // 它不会自己执行，因为对象不会监听事件。真正执行它的是 Element Plus 的 el-form-item。
  email: [
    // 当输入框失去焦点时，检查这个字段是否为空。失去焦点就是你点进邮箱框，然后又点到别的地方。
    { required: true, message: '请输入邮箱', trigger: 'blur' },// 非空校验：失去焦点时触发
    { type: 'email', message: '邮箱格式错误', trigger: ['blur', 'change'] }// 格式校验：失去焦点/内容变化时触发
  ],
  nickName: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, message: '用户名长度不能少于3位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      // 当确认密码输入框失去焦点，或者点击注册触发整体校验时，Element Plus 会执行这个函数，并且把当前字段值传给 value。这里的 value 就是：
      // registerForm.confirmPassword
      // value：当前输入框的值（确认密码）
      // callback：固定格式，报错传 Error，成功不传参数
      validator: (_, value, callback) => { // 自定义验证规则
        if (value !== registerForm.password) {
          callback(new Error('两次密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur' // 失去焦点时触发自定义校验
    }
  ]
}
// handleRegister()
// → registerFormRef.value.validate()
// → el-form 找到自己下面所有 el-form-item
// → 每个 el-form-item 根据自己的 prop 找对应字段
// → email 检查 registerRules.email
// → nickName 检查 registerRules.nickName
// → password 检查 registerRules.password
// → confirmPassword 检查 registerRules.confirmPassword
// → 只要一个失败，validate() 就失败
// → 全部成功，才继续执行后面的注册请求
const handleRegister = async () => {
  // 边界处理：防止表单引用未初始化（比如页面还没挂载完成就点击按钮）
  if (!registerFormRef.value) return

  try {
    // 第一步：触发表单全局校验（所有表单项按 rules 校验）
    // 拿到页面上的 el-form 表单组件，然后调用它的 validate() 方法，让它检查整个表单。
    // 让 Element Plus 按照 model + rules + prop 的对应关系，把整个表单检查一遍。
    await registerFormRef.value.validate()

    // 第二步：校验通过，开启加载状态（按钮转圈，防止重复点击）
    loading.value = true

    // 第三步：调用 Pinia 仓库的注册方法，传入表单数据
    // registerAction 封装了后端接口调用逻辑，返回 { success: boolean, message: string }
    const result = await userStore.registerAction({
      email: registerForm.email,
      nickName: registerForm.nickName,
      password: registerForm.password
    })

    // 第四步：根据注册结果处理
    if (result.success) {
      ElMessage.success('注册成功，请登录') // 成功提示
      router.push('/login') // 跳转到登录页
    } else {
      // 失败提示：优先显示接口返回的错误信息，无则显示默认提示
      ElMessage.error(result.message || '注册失败')
    }
  } catch (error) {
    // 异常处理：表单校验失败/代码执行出错时触发
    console.error('注册失败:', error) // 控制台打印错误，便于调试
    // 可选：给用户提示表单校验失败
    // ElMessage.error('表单填写有误，请检查后重试')
  } finally {
    // 第五步：无论成功/失败，最终关闭加载状态（按钮恢复可点击）
    loading.value = false
  }
}


</script>

<style scoped>
.register-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary-gradient);
  position: relative;
  overflow: hidden;
}

.register-box {
  width: 420px;
  padding: 48px;
  background: var(--bg-secondary);
  border-radius: var(--border-radius-lg);
  box-shadow: var(--shadow-heavy);
  position: relative;
  z-index: 10;
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
}

.register-header {
  text-align: center;
  margin-bottom: var(--spacing-xl);
}

.logo {
  margin-bottom: var(--spacing-md);
}

.logo-icon {
  font-size: 48px;
  color: var(--primary-color);
  background: var(--primary-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.register-header h2 {
  color: var(--text-primary);
  margin-bottom: 8px;
  font-size: 28px;
  font-weight: 600;
}

.register-header p {
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.5;
}

.register-form {
  margin-top: 24px;
}

.custom-input :deep(.el-input__wrapper) {
  border-radius: var(--border-radius);
  box-shadow: 0 0 0 1px var(--border-color);
  transition: all 0.3s ease;
}

.custom-input :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary-color);
}

.custom-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px var(--primary-color);
}

.register-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 600;
  border-radius: var(--border-radius);
  background: var(--primary-gradient);
  border: none;
  transition: all 0.3s ease;
}

.register-btn:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-medium);
  background: var(--primary-hover);
}

.register-btn:active {
  transform: translateY(0);
}

.register-footer {
  text-align: center;
  margin-top: 24px;
}

.login-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  transition: all 0.3s ease;
}

.login-link:hover {
  transform: translateY(-1px);
}

.bg-decoration {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.circle {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  animation: float 6s ease-in-out infinite;
}

.circle-1 {
  width: 200px;
  height: 200px;
  top: 10%;
  left: 10%;
  animation-delay: 0s;
}

.circle-2 {
  width: 150px;
  height: 150px;
  top: 60%;
  right: 15%;
  animation-delay: 2s;
}

.circle-3 {
  width: 100px;
  height: 100px;
  bottom: 20%;
  left: 20%;
  animation-delay: 4s;
}

@keyframes float {
  0%, 100% {
    transform: translateY(0px) rotate(0deg);
  }
  50% {
    transform: translateY(-20px) rotate(180deg);
  }
}

@media (max-width: 480px) {
  .register-box {
    width: 90%;
    padding: 32px;
  }

  .register-header h2 {
    font-size: 24px;
  }
}
</style>
