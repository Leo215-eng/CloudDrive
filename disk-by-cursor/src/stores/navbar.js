// 从pinia库导入创建仓库的核心方法 defineStore
import { defineStore } from 'pinia'
// 从vue导入ref，用来创建响应式变量（变量变页面自动刷新）
import { ref } from 'vue'

// 导出全局导航栏仓库，项目所有组件都能导入使用
// defineStore
// Pinia 专用方法，用来创建一个全局共享数据仓库，所有页面可以共用里面的数据和函数，不用父子组件层层传值。
// 第一个参数navbar是仓库名字，全局唯一。
// 'navbar' 是仓库唯一id，区分其他仓库（用户仓库、文件仓库等）
export const useNavbarStore = defineStore('navbar', () => {
  // 响应式变量 active：记录当前侧边栏选中的菜单标识，默认初始值 Files（全部文件）
  const active = ref('Files')

  // 修改选中菜单的方法，接收菜单名称name，赋值给active实现全局状态更新
  // 唯一修改 active 值的方法，传入菜单标识，更新全局选中状态；
  const change = (name) => {
    active.value = name
  }

  // 把变量和方法暴露出去，其他页面导入仓库后就能读取、调用
  return {
    active,
    change
  }
})
