import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getFileList, getBreadcrumbs } from '@/api/file'
import { useUserStore } from './user'
// 这些写在 defineStore 里的 parentId、files、breadcrumbs 等，是 Pinia 管理的全局单例状态，全项目共享同一份数据
// 默认值在 store 创建时赋；登录成功后 initRoot() 是第一次业务初始化；
// 进入文件管理页时 Files.vue 的 watch(..., immediate: true) 会再次调用 loadFiles()，通常就是第二次赋值。
export const useFileStore = defineStore('file', () => {
  const parentId = ref('')
  const currentFolderId = ref('') // 当前文件夹ID，用于上传
  const fileTypes = ref('-1')
  const files = ref([])
  const breadcrumbs = ref([])
  const latestLoadSeq = ref(0)

  // 支持传fileTypes参数
  /**
   * 加载当前目录下文件列表 + 面包屑导航
   * @param {string|number} newParentId 可选：目标父文件夹ID，切换目录时传入
   * @param {Array} newFileTypes 可选：文件类型筛选条件
   */
  async function loadFiles(newParentId, newFileTypes) {
    // 自增当前请求序列号，标记本次加载请求
    // latestLoadSeq 是仓库里的全局响应式变量，初始值一般是 0，用来记录「最新一次请求的编号」；
    // ++latestLoadSeq.value 是前置自增：先把全局序号 +1，再把加完后的值赋值给 currentSeq；
    // currentSeq 是本次函数内的局部变量，专门记录「这一次请求的编号」，请求过程中不会变
    const currentSeq = ++latestLoadSeq.value

    // 如果传入了文件类型筛选参数，更新全局筛选条件
    if (typeof newFileTypes !== 'undefined') fileTypes.value = newFileTypes

    // 如果传入了父文件夹ID，更新当前目录ID
    if (typeof newParentId !== 'undefined') {
      // parentId：站在子文件的视角，接下来要查的子文件，它们的父目录 ID；
      // currentFolderId：站在用户浏览的视角，用户当前正在看的文件夹 ID。
      parentId.value = newParentId
      currentFolderId.value = newParentId
    }

    // 调用后端接口，查询当前目录下的文件列表
    const res = await getFileList({
      parentId: parentId.value,
      fileTypes: fileTypes.value
    })

    // 关键防抖逻辑：如果后续又发起了新加载请求，本次旧请求结果直接丢弃，不渲染
    // 请求回来后比对编号：如果不是最新的编号，说明已经有新请求发出去了，这次旧结果直接扔掉，不更新页面。
    if (currentSeq !== latestLoadSeq.value) return

    // 赋值文件列表到页面响应式数据
    files.value = res.data || []

    // 存在父目录，需要查询面包屑导航路径
    if (parentId.value) {
      // 请求获取当前文件夹的层级面包屑
      const bcRes = await getBreadcrumbs({ fileId: parentId.value })
      // 再次校验序列号，防止旧请求覆盖最新数据
      if (currentSeq !== latestLoadSeq.value) return
      breadcrumbs.value = bcRes.data || []
    } else {
      // 根目录，清空面包屑
      breadcrumbs.value = []
    }
  }

// 用户登录成功后，把文件仓库的状态重置到「初始默认状态」：
// 定位到用户的个人根目录、显示所有类型的文件、加载出第一屏文件列表，相当于网盘登录后进入的「首页默认状态」。
// 初始化（登录后调用）
  async function initRoot() {
    // 获取全局唯一用户仓库实例，拿用户的个人根目录信息
    const userStore = useUserStore()

    // 只有用户根目录ID存在（登录成功、用户信息加载完成），才执行初始化
    if (userStore.rootFileId) {
      // 1. 给父目录ID赋值为用户根目录ID
      parentId.value = userStore.rootFileId
      // 2. 设置当前正在浏览的文件夹ID为根目录ID
      currentFolderId.value = userStore.rootFileId
      // 3. 设置文件筛选类型为 -1（代表全部类型，不筛选）
      fileTypes.value = '-1'
      // 4. 调用加载方法，拉取根目录下的所有文件
      await loadFiles(userStore.rootFileId, '-1')
    }
  }


  // 刷新文件列表（上传完成后调用）
  async function loadFileList() {
    await loadFiles(currentFolderId.value, fileTypes.value)
  }

  return { 
    parentId, 
    currentFolderId, 
    fileTypes, 
    files, 
    breadcrumbs, 
    loadFiles, 
    initRoot,
    loadFileList
  }
})
