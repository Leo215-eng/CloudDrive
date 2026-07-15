<template>
  <!-- 用户信息下拉整体容器，放在页面顶部Header右上角 -->
  <div class="pan-user-info-content">
    <!-- ElementPlus下拉组件，点击弹出菜单，选中菜单项触发handleCommand -->
<!--    @command 是 Element Plus 下拉菜单 el-dropdown 自带的点击菜单项事件，你点任意一条下拉选项，它会自动把当前选项上写的 command 值传给事件函数。-->
    <el-dropdown @command="handleCommand">
      <!-- 展示区：头像 + 用户名 + 向下箭头 -->
      <span class="user-info">
        <!-- 用户头像组件 -->
        <el-avatar
            :size="36"
          :src="userStore.userInfo.profilePhotoUrl || userStore.userInfo.avatar"
          :icon="!userStore.userInfo.profilePhotoUrl && !userStore.userInfo.avatar ? 'UserFilled' : undefined"
          class="user-avatar"
        />
        <!-- 用户名展示：有昵称显示昵称，无昵称显示账号，都没有默认显示“用户” -->
        <span class="username">{{ userStore.userInfo.nickname || userStore.userInfo.username || '用户' }}</span>
        <!-- 下拉箭头图标 -->
        <el-icon class="dropdown-icon"><ArrowDown /></el-icon>
      </span>

      <!-- #dropdown 插槽：点击头像弹出的下拉菜单内容 -->
      <template #dropdown>
        <el-dropdown-menu>
          <!-- 个人资料选项，command作为唯一标识传给点击事件 -->
          <el-dropdown-item command="profile">
            <el-icon><User /></el-icon>
            个人资料
          </el-dropdown-item>
          <!-- 更换头像选项 -->
          <el-dropdown-item command="avatar">
            <el-icon><Picture /></el-icon>
            更换头像
          </el-dropdown-item>
          <!-- divided 增加分割线，区分上面两个功能和退出登录 -->
          <el-dropdown-item divided command="logout">
            <el-icon><SwitchButton /></el-icon>
            退出登录
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
// 引入用户Pinia仓库，存放登录后的头像、昵称、退出方法
import { useUserStore } from '@/stores/user'
// 路由工具，退出后跳登录页用
import { useRouter } from 'vue-router'
// Element弹窗确认框、顶部轻提示
import { ElMessageBox, ElMessage } from 'element-plus'
// 页面用到的所有图标
import { ArrowDown, User, Picture, SwitchButton } from '@element-plus/icons-vue'

// 获取用户仓库实例
const userStore = useUserStore()
// 获取路由实例
const router = useRouter()

/**
 * 下拉菜单点击统一处理函数
 * @param command 菜单项标记：profile/avatar/logout
 */
const handleCommand = async (command) => {
  switch (command) {
      // 退出登录
    case 'logout':
      try {
        // 弹出确认弹窗，询问是否确认退出
        await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })
        // 调用仓库logout方法：清空本地token、用户信息、登录状态
        userStore.logout()
        // 页面跳转至登录页
        router.push('/login')
      } catch {
        // 用户点取消，捕获弹窗取消抛出的异常，不执行任何操作
      }
      break
      // 个人资料，功能未开发，弹出提示文字
    case 'profile':
      ElMessage.info('个人资料功能开发中...')
      break
      // 更换头像，功能未开发，弹出提示文字
    case 'avatar':
      ElMessage.info('更换头像功能开发中...')
      break
  }
}
</script>


<style scoped>
.pan-user-info-content {
  display: flex;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 8px;
  transition: all 0.3s ease;
  background: transparent;
  border: 1px solid transparent;
  gap: 8px;
}

.user-info:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  border-color: rgba(255, 255, 255, 0.3);
}

.user-avatar {
  border: 2px solid rgba(255, 255, 255, 0.2);
  transition: all 0.3s ease;
}

.user-info:hover .user-avatar {
  border-color: #fff;
  transform: scale(1.05);
}

.username {
  color: #fff;
  font-weight: 500;
  font-size: 14px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  opacity: 0.9;
}

.dropdown-icon {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
  transition: all 0.3s ease;
}

.user-info:hover .dropdown-icon {
  color: #fff;
  transform: rotate(180deg);
  opacity: 1;
}
</style>

