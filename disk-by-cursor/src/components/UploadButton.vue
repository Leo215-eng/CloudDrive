<template>
  <!-- 上传按钮外层容器 -->
  <div class="upload-button-content">
    <!-- 圆角矩形上传按钮：文字+图标样式 -->
    <button
      v-if="roundFlag"
      class="upload-btn primary round"
      @click="openDialog"
    >
      上传
      <i class="upload-icon">📤</i>
    </button>
    <!-- 纯圆形图标按钮：只展示上传图标 -->
    <button
      v-if="circleFlag"
      class="upload-btn circle"
      @click="openDialog"
    >
      📤
    </button>

    <!-- 上传弹窗遮罩层，v-if控制弹窗销毁/显示 -->
    <div v-if="uploadDialogVisible" class="upload-dialog-overlay" @click="closeDialog">
      <!-- 弹窗主体盒子 @click.stop 阻止冒泡，点击弹窗内部不会关闭弹窗 -->
      <div class="upload-dialog" @click.stop>
        <!-- 弹窗头部：标题+关闭按钮 -->
        <div class="upload-dialog-header">
          <h3>文件上传</h3>
          <button class="close-btn" @click="closeDialog">×</button>
        </div>
        <!-- 弹窗主体上传区域 -->
        <div class="upload-content" id="upload-content">
          <!-- 拖拽上传区域 -->
<!--          @dragover.prevent              阻止浏览器默认拖拽行为（禁止页面直接打开文件）-->
<!--          @drop.prevent="handleDrop"     拖拽松开，读取拖入的文件列表-->
<!--          @dragenter.prevent             鼠标拖拽进入区域，阻止默认事件 -->
<!--          @dragleave.prevent             鼠标拖拽离开区域，阻止默认事件 -->
          <div
            class="drag-content"
            @click="triggerFileSelect"
            @dragover.prevent
            @drop.prevent="handleDrop"
            @dragenter.prevent
            @dragleave.prevent
          >
            <!-- 大图标展示区 -->
            <div class="drag-icon-content">
              <div class="upload-icon-large">📁</div>
            </div>
            <!-- 提示文字区域 -->
            <div class="drag-text-content">
              <span class="drag-text">将文件拖到此处，或</span>
              <span class="click-upload">点击上传</span>
            </div>
          </div>
          <!-- 真实隐藏的文件选择input，用于读取本地文件 -->
          <!-- multiple 支持多选文件，display:none 页面不可见 -->
          <input
            ref="fileInput"
            type="file"
            multiple
            style="display: none;"
            @change="handleFileSelect"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 导入Vue组合式API核心函数
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
// 分片上传核心库 simple-uploader
import Uploader from 'simple-uploader.js'
// 网盘通用工具类：单位转换、配置读取、状态枚举等
import panUtil from '@/utils/common.js'
// 文件MD5计算工具（秒传、分片校验核心）
import { MD5 } from '@/utils/md5.js'
// 文件上传接口：秒传预校验、分片合并接口
import { secUpload, mergeChunks } from '@/api/file.js'
// 全局状态管理：当前文件夹、文件列表刷新
import { useFileStore } from '@/stores/file.js'
// 全局上传任务列表：进度、状态弹窗
import { useTaskStore } from '@/stores/task.js'
// 用户状态：token、根目录ID
import { useUserStore } from '@/stores/user.js'

// 父组件传入的按钮样式控制参数
const props = defineProps({
  // 是否展示矩形带文字上传按钮
  roundFlag: {
    type: Boolean,
    default: true
  },
  // 是否展示圆形图标上传按钮
  circleFlag: {
    type: Boolean,
    default: false
  },
  // 按钮尺寸（预留扩展）
  size: {
    type: String,
    default: 'medium'
  }
})

// 初始化全局Pinia仓库
const fileStore = useFileStore()
const taskStore = useTaskStore()
const userStore = useUserStore()

// 控制上传弹窗显示/隐藏
const uploadDialogVisible = ref(false)
// 分片上传实例对象，全局单例
let uploader = undefined
// 标记上传区域是否已绑定拖拽/点击事件，防止重复绑定
const assignFlag = ref(false)
// 绑定模板隐藏input文件选择框DOM
const fileInput = ref(null)
// 组件存活标记，组件销毁后停止所有上传回调，防止内存泄漏
let isAlive = true

/**
 * simple-uploader 分片上传全局配置项
 * 核心控制：分片大小、并发数、请求头、后端接口地址、分片校验规则
 */
const fileOptions = {
  // target：动态定义上传请求的后端接口地址
  // 开启分片上传 → 请求分片上传接口；否则普通单文件上传接口
  // file：当前正在上传的完整文件对象
  // chunk：当前正在传输的分片对象（不分片时为 null）
  target: function (file, chunk) {
    if (panUtil.getChunkUploadSwitch()) {
      return '/api/v1/files/file/chunk-upload'
    }
    return '/api/v1/files/file/upload'
  },

  singleFile: false, // false=支持一次性多选多个文件上传
  chunkSize: panUtil.getChunkSize(), // 单块分片大小，比如5MB、10MB，大文件自动切成多块
  testChunks: panUtil.getChunkUploadSwitch(),

  // true开启分片校验：上传前先询问后端哪些分片已经传完，实现断点续传
  forceChunkSize: false, // 最后一块文件允许小于分片大小，不用强制补齐
  simultaneousUploads: 3, // 同时并发3个分片请求，控制上传并发，防止请求太多卡死
  fileParameterName: 'file',

  // 每次上传请求自动拼接 URL 查询参数（?parentId=xxx）
  // 业务含义：parentId 文件上传到哪个文件夹，后端根据这个 ID 存入对应目录
  // query：每一次上传请求，自动携带额外GET参数
  query: function (file, chunk) {
    return {
      parentId: fileStore.currentFolderId || '0'
      // parentId = 文件要上传到哪个文件夹，传给后端做目录存储
    }
  },

  // headers：每次上传请求携带请求头，用于登录鉴权
  // 后端拦截器读取 Authorization 校验登录状态，没 token 直接返回 401 无权限
  headers: function () {
    return {
      Authorization: `${userStore.token}`
      // token凭证，后端校验当前登录用户是否有权限上传
    }
  },

  // 断点续传核心：后端返回已上传分片，判断当前分片是否不需要重复上传
  // chunk：当前待上传分片对象，chunk.offset = 当前分片序号（从 0 开始）
  // message：后端分片预校验接口返回的原始字符串
  // 解析后端返回的 JSON；解析失败直接判定分片不存在，需要上传
  // 后端返回 uploadedChunks: [1,2,3] 代表 1、2、3 号分片已上传
  // chunk.offset 是 0 开始，所以 + 1 和后端分片编号对齐
  // 找到当前分片编号 → return true，库自动跳过本次分片上传；找不到则正常上传
  // true：分片已存在，跳过不上传
  // false：分片缺失，正常发起上传请求
  checkChunkUploadedByResponse: function (chunk, message) {
    let objMessage = {}
    try {
      objMessage = JSON.parse(message)
    } catch (e) {
      // 后端返回不是标准JSON，代表没有已上传分片
    }
    // 后端返回数组 uploadedChunks，存着已经上传完成的分片编号
    if (objMessage.data) {
      // 当前分片编号是否存在数组里，存在=true 跳过上传
      return (objMessage.data.uploadedChunks || []).indexOf(chunk.offset + 1) >= 0
    }
    return false
  },

  maxChunkRetries: 0, // 分片上传失败，不自动重试
  chunkRetryInterval: null,
  progressCallbacksInterval: 500,
  // 进度更新节流，每500毫秒才执行一次进度回调，避免频繁刷新页面DOM造成卡顿
  successStatuses: [200, 201, 202],
  // HTTP状态码是这三个，代表本次分片请求成功
  permanentErrors: [404, 415, 500, 501],
  // 遇到这些错误码，判定永久失败，不再重复上传分片
  initialPaused: false // 文件添加后自动开始上传，不会暂停
}


/**
 * 关闭上传弹窗，清理拖拽/点击绑定，防止重复绑定事件
 */
const closeDialog = () => {
  uploadDialogVisible.value = false
  // 重置绑定标记，下次打开弹窗重新绑定上传区域
  assignFlag.value = false

  // 解绑拖拽、点击文件选择DOM事件
  if (uploader) {
    if (typeof uploader.unAssignBrowse === 'function') {
      uploader.unAssignBrowse()
    }
    if (typeof uploader.unAssignDrop === 'function') {
      uploader.unAssignDrop()
    }
  }
}

/**
 * 打开上传弹窗
 */
/**
 * 点击上传按钮，唤起上传弹窗
 */
const openDialog = () => {
  // 防止重复弹窗：弹窗已经显示就直接退出函数，不执行后面逻辑
  if (uploadDialogVisible.value) {
    return
  }

  // 重置绑定标记，代表当前还没有给DOM绑定拖拽/点击监听
  assignFlag.value = false
  // 控制弹窗显示：响应式变量修改，Vue会触发DOM更新
  uploadDialogVisible.value = true

  /**
   * nextTick 作用：
   * 修改 uploadDialogVisible 只是告诉Vue要更新页面，DOM不会立刻生成
   * nextTick 内部回调会等待Vue完成DOM渲染、页面刷新后再执行
   * 这里必须等弹窗#upload-content盒子真实存在，才能执行绑定拖拽
   */
  nextTick(() => {
    rebindUploader()
  })
}


/**
 * 点击上传区域，触发隐藏input文件选择框
 */
const triggerFileSelect = () => {
  if (fileInput.value) {
    fileInput.value.click()
  }
}

/**
 * input选择文件后触发，将文件交给uploader上传实例
 * @param event input change原生事件
 */
const handleFileSelect = (event) => {
  // 伪数组转标准数组
  const files = Array.from(event.target.files)
  if (files.length > 0 && uploader) {
    uploader.addFiles(files)
    // 清空input value，允许重复选择同一个文件
    event.target.value = ''
  }
}

/**
 * 拖拽文件到上传区域松手触发
 * @param event 拖拽drop原生事件
 */
const handleDrop = (event) => {
  event.preventDefault()
  const files = Array.from(event.dataTransfer.files)
  if (files.length > 0 && uploader) {
    uploader.addFiles(files)
  }
}

/**
 * 重新绑定上传拖拽、点击区域DOM
 * 每次打开弹窗执行，防止重复绑定造成多次上传
 */
/**
 * 给弹窗上传区域绑定 simple-uploader 的拖拽、点击选文件能力
 * 多次打开弹窗时，先解绑旧监听，再重新绑定，避免重复触发上传
 */
const rebindUploader = () => {
  // 兜底判断：浏览器不支持上传库，直接提示并终止
  if (uploader && !uploader.support) {
    alert('本浏览器不支持simple-uploader，请更换浏览器重试')
    return
  }

  // 条件：上传实例存在 + 当前未绑定监听，才执行绑定逻辑
  if (uploader && !assignFlag.value) {
    // 获取弹窗内拖拽容器DOM节点
    const uploadContent = document.getElementById('upload-content')
    if (uploadContent) {
      // 第一步：先解绑上一次弹窗残留的DOM监听，防止重复绑定
      if (typeof uploader.unAssignBrowse === 'function') {
        uploader.unAssignBrowse() // 移除点击唤起文件框的监听
      }
      if (typeof uploader.unAssignDrop === 'function') {
        uploader.unAssignDrop() // 移除拖拽文件的原生事件监听
      }

      // 第二步：重新给当前弹窗容器绑定监听
      uploader.assignBrowse(uploadContent)
      // assignBrowse：给DOM绑定click事件，点击自动唤起本地文件选择框
      uploader.assignDrop(uploadContent)
      // assignDrop：给DOM绑定 dragenter/dragover/dragleave/drop 拖拽原生事件

      // 标记已完成绑定，下次打开弹窗不会重复执行绑定逻辑
      assignFlag.value = true
    }
  }
}


/**
 * 用户选中/拖拽文件后触发（核心入口）
 * 1. 校验文件大小
 * 2. 创建上传任务存入全局taskStore
 * 3. 计算文件MD5，调用秒传接口secUpload
 * 4. 存在云端相同文件直接秒传，不存在则恢复分片上传
 */
/**
 * simple-uploader 库触发的 filesAdded 回调
 * 触发时机：用户拖拽/选中本地文件，执行 uploader.addFiles() 后自动执行
 * 核心业务：文件校验、创建上传任务、计算MD5、调用秒传接口判断是否需要真实上传
 * @param {Array} files 当前本次新增的文件实例数组（库封装的File对象）
 * @param {Array} fileList 上传器全部文件队列
 * @param {Event} event 原生拖拽/文件选择事件对象
 * @returns {boolean} 返回true代表允许加入上传队列，false拒绝加入
 */
const filesAdded = (files, fileList, event) => {
  // 防护判断：组件已经被销毁、上传实例不存在，直接终止逻辑，防止控制台报错、内存泄漏
  if (!isAlive || !uploader) return false
  // 选中文件后自动关闭上传弹窗，不需要停留在弹窗页面
  uploadDialogVisible.value = false

  try {
    // 循环处理每一个选中的文件
    files.forEach((f) => {
      // 1. 先暂停自动上传，阻塞分片传输
      // 原因：需要先算MD5、调用秒传接口，确认没有云端相同文件再开启上传
      f.pause()

      // 2. 文件大小校验，超出系统限制直接抛异常拦截全部上传
      if (f.size > panUtil.getMaxFileSize()) {
        throw new Error('文件：' + f.name + '大小超过了最大上传文件的限制（' + panUtil.translateFileSize(panUtil.getMaxFileSize()) + '）')
      }

      // 3. 组装上传任务对象，存入全局任务仓库，底部上传面板读取该数据展示进度
      let taskItem = {
        target: f,                          // 当前文件上传实例，用于暂停/取消上传
        filename: f.name,                   // 文件名称
        fileSize: panUtil.translateFileSize(f.size), // 总大小，字节转成易读单位（MB/GB）
        uploadedSize: panUtil.translateFileSize(0), // 已上传大小，初始0
        status: panUtil.fileStatus.PARSING.code,      // 任务状态：正在解析MD5
        statusText: panUtil.fileStatus.PARSING.text,
        timeRemaining: panUtil.translateTime(Number.POSITIVE_INFINITY), // 剩余时间初始无穷大
        speed: panUtil.translateSpeed(f.averageSpeed), // 上传速度
        percentage: 0,                      // 上传进度百分比初始0
        parentId: String(fileStore.currentFolderId || '0') // 目标上传文件夹ID
      }

      // 将当前文件任务添加到全局上传任务列表
      taskStore.add(taskItem)
      // 自动展开页面底部上传任务面板，展示所有上传进度
      taskStore.updateViewFlag(true)

      // 4. 读取本地文件二进制，计算MD5哈希值（文件唯一指纹）
      // 第一个参数：f.file → 需要计算哈希的本地文件二进制对象
      // 第二个参数：(e, md5) => {} → 回调函数，由 MD5 库内部主动执行
      MD5(f.file, (e, md5) => {
        // 将MD5赋值给文件内置唯一标识，用于分片断点续传、后端秒传匹配
        f['uniqueIdentifier'] = md5

        // 5. 请求后端秒传接口，校验云端是否存在相同MD5文件
//         只要函数返回 Promise，就能链式调用 .then()、.catch()。
// secUpload 发网络请求是异步，不会阻塞代码，请求成功后自动执行 .then 里的回调函数。
        secUpload({
          filename: f.name,
          identifier: md5,
          // 如果为空 /undefined，兜底默认根目录 '0'。
          parentId: fileStore.currentFolderId || '0'
        }).then(res => {
          // 后端返回成功，代表服务器已存在一模一样的文件，直接秒传完成
          if (res.code === 0 || res.success === true) {
            f.cancel() // 取消当前文件分片上传队列，无需发起HTTP请求
            taskStore.remove(f.name) // 从底部任务列表移除该任务
            fileStore.loadFileList() // 刷新当前文件夹文件列表，秒传文件立刻展示

            // 判断上传队列是否已无待上传文件，关闭底部上传面板
            if (uploader && Array.isArray(uploader.files) && uploader.files.length === 0) {
              taskStore.updateViewFlag(false)
            }
          } else {
            // 云端无匹配文件，需要正常分片上传
            f.resume() // 恢复文件上传，库自动切割分片并发请求
            // 更新任务状态为等待上传
            taskStore.updateStatus({
              filename: f.name,
              status: panUtil.fileStatus.WAITING.code,
              statusText: panUtil.fileStatus.WAITING.text
            })
          }
        }).catch(err => {
          // 秒传接口网络/服务异常，放弃秒传逻辑，正常执行分片上传
          f.resume()
          taskStore.updateStatus({
            filename: f.name,
            status: panUtil.fileStatus.WAITING.code,
            statusText: panUtil.fileStatus.WAITING.text
          })
        })
      })
    })
  } catch (e) {
    // 捕获文件大小超限抛出的异常
    alert(e.message)
    // 终止所有正在进行的上传请求
    if (uploader && typeof uploader.cancel === 'function') {
      try { uploader.cancel() } catch (err) {}
    }
    // 清空全部上传任务
    taskStore.clear()
    // 返回false，拒绝文件加入上传队列
    return false
  }

  // 确保底部上传面板保持展开
  taskStore.updateViewFlag(true)
  // 返回true，允许文件加入上传队列
  return true
}


/**
 * 分片上传进度实时回调
 * 更新全局任务进度、速度、剩余时间、百分比
 */
/**
 * simple-uploader 分片上传进度回调
 * 触发时机：文件分片正在传输时，按 progressCallbacksInterval 节流持续触发
 * 作用：实时更新全局上传任务的进度、速度、剩余时间，供页面进度面板渲染
 * @param rootFile 顶层文件对象（多文件上传时顶层封装对象）
 * @param file 当前正在传输的单个文件实例（库内部File对象）
 * @param chunk 当前正在传输的分片对象
 */
const uploadProgress = (rootFile, file, chunk) => {
  // 防护判断：如果组件已经销毁，直接终止执行，防止组件卸载后回调报错
  if (!isAlive) return

  // 根据文件名，从全局Pinia任务仓库取出对应这条文件的上传任务
  let uploadTaskItem = taskStore.getUploadTask(file.name)

  // 判断：该文件当前正处于上传传输状态
  if (file.isUploading()) {
    // 如果任务状态不是「上传中」，就更新状态为上传中
    if (uploadTaskItem.status !== panUtil.fileStatus.UPLOADING.code) {
      taskStore.updateStatus({
        filename: file.name,
        status: panUtil.fileStatus.UPLOADING.code,
        statusText: panUtil.fileStatus.UPLOADING.text
      })
    }

    // 更新实时进度数据存入全局仓库
    taskStore.updateProcess({
      filename: file.name,
      // averageSpeed：库计算出实时上传速度，工具转换成易读单位（KB/s、MB/s）
      speed: panUtil.translateSpeed(file.averageSpeed),
      // progress 是 0~1 的小数，乘100取整转为百分比数字
      percentage: Math.floor(file.progress() * 100),
      // sizeUploaded：当前已上传字节，转换为可读大小
      uploadedSize: panUtil.translateFileSize(file.sizeUploaded()),
      // timeRemaining：库算出剩余秒数，工具转成 xx秒/xx分钟
      timeRemaining: panUtil.translateTime(file.timeRemaining())
    })
  }
}


/**
 * 所有分片上传完成后，调用后端分片合并接口
 * @param file 当前完整文件对象
 */
/**
 * 所有分片上传完成后，调用后端接口合并分片
 * 触发时机：fileUploaded 判断全部分片上传完毕时调用
 * @param {File} file simple-uploader封装的当前完整文件实例
 */
const doMerge = (file) => {
  // 组件已销毁直接退出，防止异步回调执行时报错、操作已销毁的页面仓库
  if (!isAlive) return

  // 根据文件名从全局Pinia任务仓库取出这条文件的上传任务
  let uploadTaskItem = taskStore.getUploadTask(file.name)

  // 更新上传任务状态：变更为【分片中】
  taskStore.updateStatus({
    filename: file.name,
    status: panUtil.fileStatus.MERGE.code,
    statusText: panUtil.fileStatus.MERGE.text
  })
  // 更新进度面板数值：固定99%，代表分片全部传完正在后台拼接
  taskStore.updateProcess({
    filename: file.name,
    speed: panUtil.translateSpeed(file.averageSpeed),
    percentage: 99,
    uploadedSize: panUtil.translateFileSize(file.sizeUploaded()),
    timeRemaining: panUtil.translateTime(file.timeRemaining())
  })

  // 调用后端分片合并接口，传递拼接需要的全部参数
  mergeChunks({
    filename: uploadTaskItem.filename,        // 文件原始名称
    identifier: uploadTaskItem.target.uniqueIdentifier, // 文件MD5标识，后端匹配分片
    parentId: uploadTaskItem.parentId,        // 文件存放目标文件夹ID
    totalSize: uploadTaskItem.target.size     // 文件完整总字节大小
  }).then(res => {
    // 后端合并分片业务成功
    if (res.code === 0 || res.success === true) {
      // 从上传器队列移除该文件，不再占用上传队列
      if (uploader && typeof uploader.removeFile === 'function') {
        uploader.removeFile(file)
      }
      // 刷新当前目录文件列表，展示刚上传完成的文件
      fileStore.loadFileList()
      // 修改任务状态为上传成功
      taskStore.updateStatus({
        filename: file.name,
        status: panUtil.fileStatus.SUCCESS.code,
        statusText: panUtil.fileStatus.SUCCESS.text
      })
      // 从底部上传任务面板删除本条任务
      taskStore.remove(file.name)
      // 如果上传队列没有剩余文件，自动收起底部上传面板
      if (uploader && Array.isArray(uploader.files) && uploader.files.length === 0) {
        taskStore.updateViewFlag(false)
      }
    } else {
      // 后端返回业务失败（分片缺失、拼接异常等）
      file.pause() // 暂停该文件所有上传流程
      taskStore.updateStatus({
        filename: file.name,
        status: panUtil.fileStatus.FAIL.code,
        statusText: panUtil.fileStatus.FAIL.text
      })
    }
  }).catch(err => {
    // 合并接口网络异常、500、404等网络层面错误
    file.pause()
    taskStore.updateStatus({
      filename: file.name,
      status: panUtil.fileStatus.FAIL.code,
      statusText: panUtil.fileStatus.FAIL.text
    })
  })
}


/**
 * 单个分片上传完成回调
 * 判断是否需要合并分片，调用doMerge
 */
/**
 * simple-uploader 单个分片上传成功回调
 * 触发时机：每一个分片单独请求HTTP接口成功后自动执行
 * 作用：解析后端返回结果，判断是否全部分片上传完成，需要调用合并分片接口
 * @param rootFile 顶层文件封装对象（多文件队列顶层容器，业务基本不用）
 * @param file 当前完整文件实例（整个大文件对象，包含所有分片信息）
 * @param message 后端分片上传接口返回的原始响应文本字符串
 * @param chunk 当前刚上传完成的单个分片对象
 */
const fileUploaded = (rootFile, file, message, chunk) => {
  // 防护拦截：组件已经销毁，直接终止，防止页面卸载后回调报错
  if (!isAlive) return

  // 初始化接收后端返回数据的对象
  let res = {}
  try {
    // 将后端返回的字符串转成JSON对象，方便读取code、data等字段
    res = JSON.parse(message)
  } catch (e) {
    // 转换失败：后端返回不是标准JSON，直接跳过后续判断
  }

  // 判断后端返回业务成功（code=0 / success=true 代表本次分片上传成功）
  if (res.code === 0 || res.success === true) {
    // 后端有返回data字段，代表开启了分片上传逻辑
    if (res.data) {
      // 两种条件满足任意一种，说明所有分片已经上传完毕，需要后端拼接文件
      // 条件1：后端主动标记需要合并文件 mergeFlag=true
      if (res.data.mergeFlag) {
        doMerge(file)
      }
      // 条件2：后端返回已上传分片列表长度 = 文件总分片数量，代表全部传完
      else if (res.data.uploadedChunks && res.data.uploadedChunks.length === file.chunks.length) {
        doMerge(file)
      }
    } else {
      // 无data数据 = 未开启分片，小文件一次性上传完成，无需合并
      // 从上传队列移除当前文件
      if (uploader && typeof uploader.removeFile === 'function') {
        uploader.removeFile(file)
      }
      // 刷新当前目录文件列表，新文件立刻展示在页面
      fileStore.loadFileList()
      // 修改任务状态为上传成功
      taskStore.updateStatus({
        filename: file.name,
        status: panUtil.fileStatus.SUCCESS.code,
        statusText: panUtil.fileStatus.SUCCESS.text
      })
      // 从全局上传任务列表删除本条任务
      taskStore.remove(file.name)
      // 如果上传队列没有剩余文件，自动关闭底部上传任务面板
      if (uploader && Array.isArray(uploader.files) && uploader.files.length === 0) {
        taskStore.updateViewFlag(false)
      }
    }
  } else {
    // 后端返回业务失败（比如参数错误、存储失败等）
    file.pause() // 暂停该文件所有分片上传
    // 更新任务状态为上传失败
    taskStore.updateStatus({
      filename: file.name,
      status: panUtil.fileStatus.FAIL.code,
      statusText: panUtil.fileStatus.FAIL.text
    })
  }
}


/**
 * 全部文件上传完全结束（预留扩展）
 */
const uploadComplete = () => {
  // 上传完成
}

/**
 * 分片上传失败统一处理
 */
const uploadError = (rootFile, file, message, chunk) => {
  if (!isAlive) return
  taskStore.updateStatus({
    filename: file.name,
    status: panUtil.fileStatus.FAIL.code,
    statusText: panUtil.fileStatus.FAIL.text
  })
  // 清空进度
  taskStore.updateProcess({
    filename: file.name,
    speed: panUtil.translateSpeed(0),
    percentage: 0,
    uploadedSize: panUtil.translateFileSize(0),
    timeRemaining: panUtil.translateTime(Number.POSITIVE_INFINITY)
  })
}

/**
 * 初始化分片上传实例，绑定所有上传生命周期事件
 */
const initUploader = () => {
  // 1. 清空全局上传任务列表，防止上次上传残留进度
  taskStore.clear()

  // 2. 创建上传实例，把上面一大段fileOptions配置传给上传库
  // simple-uploader是专门做大文件分片上传的纯 JS 上传库，
  // 底层封装了浏览器原生文件 API、AJAX 分片请求、拖拽监听、断点续传逻辑。
//   fileOptions：你给库的规则配置（接口地址、分片大小、请求头、成功状态码等）；
// uploader = new Uploader(配置)：创建一个独立的上传实例，拥有自己的文件队列、请求、事件系统。
  uploader = new Uploader(fileOptions)

  // 3. 判断浏览器是否支持分片上传，不支持直接弹窗提示
  if (!uploader.support) {
    alert('本浏览器不支持simple-uploader，请更换浏览器重试')
    return
  }

  // 4. 给上传实例绑定【上传库自己的生命周期事件】
  // uploader.on("事件名", 自己写的回调函数) .on() 是库内置的「事件监听注册方法」
  uploader.on("filesAdded", filesAdded)        // 用户选中/拖拽文件后触发
  uploader.on("fileProgress", uploadProgress)   // 文件分片上传时，持续返回进度
// fileUploaded 不是接口名，也不是上传方式。fileUploaded 是“上传请求成功后的统一处理函数”。
// 切片上传：/chunk-upload 成功 → 进入 fileUploaded → res.data 有值 → 判断是否 merge。
// 普通上传：/upload 成功 → 进入 fileUploaded → res.data 没值 → 直接刷新列表。**
//   `fileUploaded` 本身**不是推动下一个切片上传的发动机**，它只是一个“监听成功结果后的业务回调”。
//   **切片上传时真正负责连续上传的是：simple-uploader 内部队列**

  uploader.on("fileSuccess", fileUploaded)
  uploader.on("complete", uploadComplete)       // 所有文件全部上传结束（预留）
  uploader.on("fileError", uploadError)         // 某个分片上传失败
}


// 组件挂载时初始化上传器
onMounted(() => {
  initUploader()
})

// 组件销毁，销毁上传实例、解绑事件、防止内存泄漏
onUnmounted(() => {
  isAlive = false
  if (uploader) {
    // 停止所有上传任务
    if (typeof uploader.cancel === 'function') {
      try { uploader.cancel() } catch (e) {}
    }
    // 解绑拖拽、点击DOM绑定
    if (typeof uploader.unAssignBrowse === 'function') {
      uploader.unAssignBrowse()
    }
    if (typeof uploader.unAssignDrop === 'function') {
      uploader.unAssignDrop()
    }
    // 移除所有事件监听
    if (typeof uploader.off === 'function') {
      uploader.off()
    }
    uploader = undefined
  }
  // 重置状态变量
  assignFlag.value = false
  uploadDialogVisible.value = false
})
</script>


<style scoped>
.upload-button-content {
  display: inline-block;
  margin-right: 10px;
}

.upload-btn {
  border: none;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.3s;
}

.upload-btn.primary {
  background-color: #409eff;
  color: white;
  padding: 8px 16px;
  border-radius: 4px;
}

.upload-btn.primary:hover {
  background-color: #66b1ff;
}

.upload-btn.round {
  border-radius: 20px;
}

.upload-btn.circle {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background-color: #409eff;
  color: white;
  font-size: 18px;
}

.upload-btn.circle:hover {
  background-color: #66b1ff;
}

.upload-icon {
  margin-left: 5px;
}

.upload-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.upload-dialog {
  background: white;
  border-radius: 8px;
  width: 500px;
  max-width: 90vw;
  max-height: 90vh;
  overflow: hidden;
}

.upload-dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  border-bottom: 1px solid #eee;
}

.upload-dialog-header h3 {
  margin: 0;
  color: #333;
}

.close-btn {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: #999;
}

.close-btn:hover {
  color: #333;
}

.upload-content {
  width: 100%;
  height: 300px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.drag-content {
  border: 2px dashed #dcdfe6;
  border-radius: 8px;
  width: 80%;
  height: 250px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  transition: all 0.3s ease;
  cursor: pointer;
}

.drag-content:hover {
  border-color: #409eff;
  background-color: rgba(64, 158, 255, 0.05);
}

.drag-content.dragover {
  border-color: #409eff;
  background-color: rgba(64, 158, 255, 0.1);
  transform: scale(1.02);
}

.drag-icon-content {
  margin-bottom: 20px;
}

.upload-icon-large {
  font-size: 64px;
  color: #dcdfe6;
}

.drag-text-content {
  text-align: center;
}

.drag-text {
  color: #909399;
  margin-right: 5px;
}

.click-upload {
  color: #409eff;
  cursor: pointer;
}

.click-upload:hover {
  text-decoration: underline;
}
</style>
