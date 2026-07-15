/**
 * MD5计算工具
 */

import SparkMD5 from 'spark-md5'

/**
 * 分片读取大文件，计算完整文件MD5（解决超大文件一次性读取卡顿/内存溢出）
 * @param {File} file 浏览器原生File文件对象（上传选中的本地文件）
 * @param {Function} callback 回调函数，计算完成/出错时触发
 *        成功：callback(null, md5字符串)
 *        失败：callback(错误事件对象)
 */
export function MD5(file, callback) {
    // 分片读取大小：2MB = 2 * 1024 * 1024 = 2097152 字节
    const chunkSize = 2097152
    // 计算文件一共要拆分成多少块读取
    const chunks = Math.ceil(file.size / chunkSize)
    // SparkMD5：专门用来计算ArrayBuffer二进制数据MD5的工具实例
    const spark = new SparkMD5.ArrayBuffer()
    // 浏览器内置文件读取对象，异步读取本地文件二进制
    const fileReader = new FileReader()
    // 当前读到第几块分片，初始0
    let currentChunk = 0

    // 文件单块读取成功完成的回调
    fileReader.onload = function (e) {
        // 将本次读取到的二进制数据追加到MD5计算器中
        spark.append(e.target.result)
        // 读完一块，块计数+1
        currentChunk++

        // 判断是否还有剩余分片没读
        if (currentChunk < chunks) {
            // 还有分片，继续读取下一块
            loadNext()
        } else {
            // 所有分片读取完毕，算出最终MD5
            // 第一个参数null=无错误，第二个是md5结果字符串，传给外部回调
            callback(null, spark.end())
        }
    }

    // 文件读取发生错误（文件损坏、权限不足等）
    fileReader.onerror = function (e) {
        // 直接把错误对象传给外部回调，只传一个参数
        callback(e)
    }

    /**
     * 读取下一块文件分片的内部函数
     */
    function loadNext() {
        // 计算本次分片起始字节位置
        const start = currentChunk * chunkSize
        // 结束位置，防止超出文件总大小
        const end = Math.min(start + chunkSize, file.size)
        // file.slice：截取文件指定区间二进制分片
        // readAsArrayBuffer：以二进制数组形式读取分片
        // FileReader 是浏览器自带读取文件的 API，读取文件是异步操作：
        // 调用 fileReader.readAsArrayBuffer(分片) 只是告诉浏览器 “去读这块文件”，代码不会卡住等待读取完成；
        // 浏览器读完二进制分片后，会自动触发 onload 事件；
        // 你提前给 onload 赋值一个函数，浏览器读完后自动执行这个函数，把读取结果通过参数 e 传给你；
        // 参数 e 是浏览器原生事件对象，e.target.result 就是本次读取到的文件二进制数组。
        fileReader.readAsArrayBuffer(file.slice(start, end))
    }

    // 函数入口，直接启动读取第一块分片
    loadNext()
}
