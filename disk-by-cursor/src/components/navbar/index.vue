<!-- 侧边栏整体外层容器 -->
<template>
  <!--
    :class="{ 'collapsed': isCollapsed } 动态绑定样式类
    作用：侧边栏收缩/展开切换，isCollapsed为true时给外层加collapsed类，样式控制宽度变窄、隐藏文字
  -->
  <div class="pan-nav-content-wrapper" :class="{ 'collapsed': isCollapsed }">
    <!-- 侧边栏内部内容容器 -->
    <div class="pan-nav-content">
      <!-- 第一组菜单：首页 -->
      <ul class="pan-nav-home">
        <li class="pan-nav-home-li">
          <!--
            @click="handleChange('Home')：点击触发菜单切换函数，传入菜单标识Home
            :class="{'checked': active === 'Home'}" 核心高亮逻辑
              如果全局仓库active的值等于Home，给当前a标签添加checked样式类，css写checked的高亮背景/文字色
            href="javascript:void(0);" 取消a标签默认跳转行为，只用click事件控制页面
          -->
          <a @click="handleChange('Home')" :class="{'checked': active === 'Home'}" href="javascript:void(0);">
            <span class="text">
              <!-- 图标组件：首页图标 -->
              <el-icon class="icon"><House /></el-icon>
              <!-- v-show="!isCollapsed" 侧边栏不收缩时才显示文字，收缩只留图标 -->
              <span v-show="!isCollapsed">首页</span>
            </span>
          </a>
        </li>
      </ul>

      <!-- 第二组菜单：文件分类（全部文件 + 图片/文档/视频/音乐子菜单） -->
      <ul class="pan-nav-file">
        <!-- 一级菜单：全部文件 -->
        <li class="pan-nav-file-all">
          <a @click="handleChange('Files')" :class="{'checked': active === 'Files'}" href="javascript:void(0);">
            <span class="text">
              <el-icon class="icon"><Folder /></el-icon>
              <span v-show="!isCollapsed">全部文件</span>
            </span>
          </a>
        </li>

        <!-- 子菜单容器：图片、文档、视频、音乐，侧边栏收缩时整体隐藏 -->
        <ul class="sub-menu" v-show="!isCollapsed">
          <!-- 子菜单：图片 -->
          <li class="pan-nav-file-pic">
            <a @click="handleChange('Imgs')" :class="{'checked': active === 'Imgs'}" href="javascript:void(0);">
              <span class="text">
                <el-icon class="icon"><Picture /></el-icon>
                <span>图片</span>
              </span>
            </a>
          </li>
          <!-- 子菜单：文档 -->
          <li class="pan-nav-file-doc">
            <a @click="handleChange('Docs')" :class="{'checked': active === 'Docs'}" href="javascript:void(0);">
              <span class="text">
                <el-icon class="icon"><Document /></el-icon>
                <span>文档</span>
              </span>
            </a>
          </li>
          <!-- 子菜单：视频 -->
          <li class="pan-nav-file-video">
            <a @click="handleChange('Videos')" :class="{'checked': active === 'Videos'}" href="javascript:void(0);">
              <span class="text">
                <el-icon class="icon"><VideoPlay /></el-icon>
                <span>视频</span>
              </span>
            </a>
          </li>
          <!-- 子菜单：音乐 -->
          <li class="pan-nav-file-music">
            <a @click="handleChange('Musics')" :class="{'checked': active === 'Musics'}" href="javascript:void(0);">
              <span class="text">
                <el-icon class="icon"><Headset /></el-icon>
                <span>音乐</span>
              </span>
            </a>
          </li>
        </ul>
      </ul>

      <!-- 第三组菜单：我的分享 -->
      <ul class="pan-nav-share">
        <li class="pan-nav-share-li">
          <a @click="handleChange('Shares')" :class="{'checked': active === 'Shares'}" href="javascript:void(0);">
            <span class="text">
              <el-icon class="icon"><Share /></el-icon>
              <span v-show="!isCollapsed">我的分享</span>
            </span>
          </a>
        </li>
      </ul>

      <!-- 第四组菜单：回收站 -->
      <ul class="pan-nav-recycle">
        <li class="pan-nav-recycle-li">
          <a @click="handleChange('Recycle')" :class="{'checked': active === 'Recycle'}" href="javascript:void(0);">
            <span class="text">
              <el-icon class="icon"><Delete /></el-icon>
              <span v-show="!isCollapsed">回收站</span>
            </span>
          </a>
        </li>
      </ul>
    </div>
  </div>
</template>


<script setup>
// Pinia工具：把store里的state转为响应式ref
import { storeToRefs } from 'pinia'
// 导入侧边导航栏全局状态仓库
import { useNavbarStore } from '@/stores/navbar'
// vue-router组合式API：useRoute获取当前路由信息，useRouter做页面跳转
import { useRoute, useRouter } from 'vue-router'
// Vue生命周期/监听API
import { onMounted, watch } from 'vue'
// 导入侧边菜单所需图标
import {
  House,      // 首页
  Folder,     // 全部文件
  Picture,    // 图片
  Document,   // 文档
  VideoPlay,  // 视频
  Headset,    // 音乐
  Share,      // 我的分享
  Delete      // 回收站
} from '@element-plus/icons-vue'

// 定义父组件传入的props：侧边栏是否折叠
const props = defineProps({
  isCollapsed: {
    type: Boolean,
    default: false
  }
})

// 实例化导航栏Pinia仓库
const store = useNavbarStore()
// 获取当前路由对象（只读，存放路径、参数、页面name）
const route = useRoute()
// 获取路由操作实例，用于编程式跳转
const router = useRouter()

// 将仓库中的active状态转为响应式变量，模板可直接使用
const { active } = storeToRefs(store)
// 解构仓库里修改激活菜单的方法
const { change } = store

/**
 * 侧边菜单点击事件
 * @param {string} name 菜单标识：Home/Files/Imgs/Docs/Videos/Musics/Shares/Recycle
 */

const handleChange = (name) => {
  // 1. 更新全局仓库当前激活菜单
  change(name)
  // outer.currentRoute.value：拿到浏览器当前页面的路由信息
  const current = router.currentRoute.value
  // 根据菜单名称跳转对应页面
  switch (name) {
    case 'Home':
      // 不在首页则跳首页
      if (current.name !== 'Home') router.push({ name: 'Home' })
      break
    // 文件分类共用一个页面
    //全部文件 / 图片 / 文档 / 视频 / 音乐：共用Files页面，靠路由参数type区分展示数据；
      // 无 type：展示所有文件；
      // type=image：只展示图片；
      // type=document：只展示文档
    // TODO 浏览器地址里 /files?type=imag。   files 是页面路径，? 后面全部是查询参数。

    case 'Files':
      // 全部文件，清空文件类型筛选参数
      if (current.name !== 'Files' || current.query.type) {
        router.push({ name: 'Files', query: {} })
      }
      break
    // 点击左侧【图片】
    //   路由跳转 /files?type=image
    //       Files 页面读取 query.type = image，请求后端接口只查图片文件。
    case 'Imgs':
      // 图片分类，携带type=image筛选参数
      if (!(current.name === 'Files' && current.query.type === 'image')) {
        router.push({ name: 'Files', query: { type: 'image' } })
      }
      break
    case 'Docs':
      // 文档分类
      if (!(current.name === 'Files' && current.query.type === 'document')) {
        router.push({ name: 'Files', query: { type: 'document' } })
      }
      break
    case 'Videos':
      // 视频分类
      if (!(current.name === 'Files' && current.query.type === 'video')) {
        router.push({ name: 'Files', query: { type: 'video' } })
      }
      break
    case 'Musics':
      // 音乐分类
      if (!(current.name === 'Files' && current.query.type === 'music')) {
        router.push({ name: 'Files', query: { type: 'music' } })
      }
      break
    case 'Shares':
      // 我的分享页面
      if (current.name !== 'Shares') router.push({ name: 'Shares' })
      break
    case 'Recycle':
      // 回收站页面
      if (current.name !== 'Recycle') router.push({ name: 'Recycle' })
      break
  }
}

// TODO 点击侧边菜单 → handleChange 跳转路由、更新菜单状态；
// 路由发生变化 → watch 触发 updateNavbarByRoute，反向同步菜单高亮；
// 双向同步，保证地址和侧边栏高亮永远保持一致。

/**
 * 根据当前路由自动更新左侧侧边栏激活高亮菜单
 * 场景：用户手动刷新页面、浏览器前进/后退、直接输入地址访问分类文件页时，自动匹配对应菜单高亮
 * 核心逻辑：
 * 1. 如果当前是Files文件页面，读取url上的type筛选参数，匹配图片/文档/视频/音乐/全部文件菜单
 * 2. 如果不是Files页面，直接用路由name匹配首页/分享/回收站等菜单
 */
const updateNavbarByRoute = () => {
  // 当前页面是文件列表页
  if (route.name === 'Files') {
    // 取出url问号后面的筛选参数 type，例：/files?type=image 中 type = "image"
    const type = route.query.type
    // 参数是image，代表当前在图片分类，调用全局方法激活【图片】菜单、侧边栏高亮
    if (type === 'image') {
      change('Imgs')
      return  // 参数是image，代表当前在图片分类，调用全局方法激活【图片】菜单、侧边栏高亮
    }
    if (type === 'document') {
      change('Docs')
      return
    }
    if (type === 'video') {
      change('Videos')
      return
    }
    if (type === 'music') {
      change('Musics')
      return
    }
    // 上面所有type都不匹配，代表url无type参数，当前是全部文件页面，激活【全部文件】菜单
    change('Files')
    return
  }
  // 走到这里说明不是Files页面（首页/我的分享/回收站等独立页面）
  // 直接拿当前页面路由名称，传入change，自动高亮对应侧边栏菜单
  change(route.name)
}

/**
 * 路由监听：监听路由名称、文件筛选type参数变化
 * 作用：只要页面地址切换（点菜单、浏览器前进后退、手动改地址），立刻执行更新菜单高亮
 * 监听两个值：route.name（页面名称）、route.query.type（文件分类参数）
 * immediate: false 代表页面刚创建时不会自动执行，只在值变化时触发
 */
watch([() => route.name, () => route.query.type], () => {
  updateNavbarByRoute()
}, { immediate: false })

/**
 * 组件挂载生命周期：页面第一次加载完成时执行一次菜单匹配
 * 场景：用户刷新页面、直接输入网址进入页面，初始化侧边栏正确高亮
 */
onMounted(() => {
  updateNavbarByRoute()
})
</script>


<style scoped>
.checked {
  background: rgba(102, 126, 234, 0.1);
}

.checked span {
  color: #667eea !important;
}

.checked .icon {
  color: #667eea !important;
}

ul {
  list-style: none;
  padding-inline-start: 0;
  margin: 0;
}

li {
  display: list-item;
  text-align: -webkit-match-parent;
  list-style: none;
}

.pan-nav-content-wrapper {
  background-color: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
  width: 160px;
  height: fit-content;
  padding: 10px 0;
  flex-shrink: 0;
  transition: all 0.3s ease;
}

.pan-nav-content-wrapper.collapsed {
  width: 50px;
  padding: 10px 0;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .pan-nav-content-wrapper {
    width: 140px;
    padding: 6px 0;
  }
}

@media (max-width: 992px) {
  .pan-nav-content-wrapper {
    width: 120px;
    padding: 4px 0;
  }
}

.pan-nav-content ul a {
  height: 40px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  text-decoration: none;
  transition: all 0.3s ease;
}

.pan-nav-content ul a:hover {
  background: rgba(102, 126, 234, 0.05);
}

.pan-nav-content ul a:hover span {
  color: #667eea;
}

.pan-nav-content ul a:hover .icon {
  color: #667eea;
}

.pan-nav-content ul .text {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #333;
  font-size: 14px;
  font-weight: 500;
}

.pan-nav-content ul .icon {
  font-size: 18px;
  color: #666;
  transition: all 0.3s ease;
}

.pan-nav-home {
  margin-bottom: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.pan-nav-share, .pan-nav-recycle {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid rgba(0, 0, 0, 0.05);
}

.sub-menu {
  margin-left: 20px !important;
  position: relative;
}

.sub-menu::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: rgba(102, 126, 234, 0.1);
}

.sub-menu li a {
  position: relative;
  padding-left: 28px !important;
}

.sub-menu li a::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 50%;
  width: 12px;
  height: 1px;
  background: rgba(102, 126, 234, 0.1);
}

.sub-menu li a:hover::before {
  background: rgba(102, 126, 234, 0.3);
}

.sub-menu li a.checked::before {
  background: rgba(102, 126, 234, 0.3);
}

.pan-nav-file-all {
  margin-bottom: 4px;
}

.pan-nav-content ul .text {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #333;
  font-size: 14px;
  font-weight: 500;
}

.pan-nav-content ul.sub-menu .text {
  font-weight: 400;
  color: #666;
}

.pan-nav-content ul.sub-menu .icon {
  font-size: 16px;
}
</style>
