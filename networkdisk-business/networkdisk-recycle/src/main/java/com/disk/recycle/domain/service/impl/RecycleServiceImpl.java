package com.disk.recycle.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.disk.recycle.domain.entity.UserFileDO;
import com.disk.recycle.domain.service.RecycleService;
import com.disk.recycle.infrastructure.mapper.UserFileMapper;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecycleServiceImpl extends ServiceImpl<UserFileMapper, UserFileDO> implements RecycleService {

    @Override
    public List<UserFileDO> list(Long userId) {
        return baseMapper.listDeletedByUser(userId);
    }

    /**
     * 批量恢复回收站里的文件/文件夹
     * @param userId 当前登录用户ID（数据隔离）
     * @param fileIds 用户选中要恢复的文件/文件夹ID列表
     */
    @Transactional(rollbackFor = Exception.class) // 加事务：批量操作要么全成功要么全失败，出错全部回滚
    @Override
    public void restore(Long userId, List<Long> fileIds) {
        // 前置判空：没有要恢复的文件，直接返回，不做无效操作
        if (CollectionUtils.isEmpty(fileIds)) {
            return;
        }

        // 核心：递归收集所有要恢复的文件ID
        // 比如用户选了一个文件夹，要把文件夹里所有子文件、子文件夹的ID都找出来，一起恢复
        // 只传用户选的顶层ID不行，里面的子文件也要跟着恢复
        List<Long> restoreIds = collectOperationIds(userId, fileIds);

        // 递归后没有有效ID，直接返回
        if (CollectionUtils.isEmpty(restoreIds)) {
            return;
        }

        // 调用Mapper，批量更新数据库，把文件状态从「已删除」改回「正常」
        baseMapper.restoreByIds(userId, restoreIds);
    }


    /**
     * 批量彻底删除回收站里的文件/文件夹（物理删除，不可恢复）
     * @param userId 当前登录用户ID
     * @param fileIds 用户选中要彻底删除的文件/文件夹ID列表
     */
    @Transactional(rollbackFor = Exception.class) // 事务保证：批量删除要么全删成功要么全不删
    @Override
    public void hardDelete(Long userId, List<Long> fileIds) {
        // 前置判空
        if (CollectionUtils.isEmpty(fileIds)) {
            return;
        }

        // 递归收集所有要彻底删除的文件ID
        // 删文件夹必须连带里面所有子文件、子文件夹一起删掉，不能只删顶层文件夹
        List<Long> hardDeleteIds = collectOperationIds(userId, fileIds);

        // 没有有效ID直接返回
        if (CollectionUtils.isEmpty(hardDeleteIds)) {
            return;
        }

        // 调用Mapper，物理删除数据库记录
        baseMapper.hardDeleteByIds(userId, hardDeleteIds);
    }


    /**
     * 展开并收集最终要操作的文件ID列表
     * 1. 过滤：只保留当前用户回收站中真实存在的选中记录
     * 2. 递归：如果选中的是文件夹，自动补齐其所有已删除的子孙节点ID
     * @param userId 当前登录用户ID（数据隔离）
     * @param selectedIds 用户在页面选中的文件/文件夹ID
     * @return 最终要操作的完整ID列表（包含所有子孙节点）
     */
    private List<Long> collectOperationIds(Long userId, List<Long> selectedIds) {
        // ========== 第一步：查出当前用户回收站里的所有文件（一次性全量查，避免递归查库） ==========
        // listDeletedByUser：查询 user_id=当前用户 且 deleted=1 的所有记录
//        deletedRecords 当前用户回收站里的完整文件记录
        List<UserFileDO> deletedRecords = baseMapper.listDeletedByUser(userId);
        // 回收站是空的，直接返回空列表，不用往下处理
        if (CollectionUtils.isEmpty(deletedRecords)) {
            return new ArrayList<>();
        }

        // ========== 第二步：把回收站所有ID转成Set，用于O(1)快速校验 ==========
        // Set 做存在性判断，比循环列表比对快得多，数据量大时差距明显
//        .map()（方法）：流式转换，把集合里每个元素加工一遍，数量不变、类型可变；
//        deletedIdSet 当前用户回收站里所有文件的 ID
        Set<Long> deletedIdSet = deletedRecords.stream()
//                ::：方法引用，可以理解成简写版的 record -> record.getId()。
                .map(UserFileDO::getId)
//        Collectors.toSet()：收集成 Set，Set 的特点是不重复
                .collect(Collectors.toSet());

        // ========== 第三步：过滤用户选中的ID，只保留「确实在回收站里」的ID ==========
        // 作用：防止前端传错ID、传别人的文件ID、传正常文件的ID，保证只操作回收站里自己的文件
        // 用 LinkedHashSet：既去重，又保留插入顺序
//        最终要操作的 ID，后面会不断增加
        Set<Long> operationIdSet = selectedIds.stream()
                .filter(deletedIdSet::contains) // 只保留回收站里存在的ID
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 过滤后没有有效ID，直接返回空
        if (operationIdSet.isEmpty()) {
            return new ArrayList<>();
        }

        // ========== 第四步：在内存中构建目录树索引：父ID -> 子文件列表 ==========
        // 相当于把回收站里的所有文件，按「父文件夹ID」分组，后面找子节点直接get，不用循环遍历
    //     它的类型一般是 Map<Long, List<FileRecord>>：
    //    Key：父目录 ID（比如根目录是 0，一级目录的 parentId 是 0）
    //    Value：这个父目录下，所有子文件 / 子目录组成的 List 集合
        Map<Long, List<UserFileDO>> childrenMap = new HashMap<>();
        for (UserFileDO record : deletedRecords) {
            // computeIfAbsent：如果父ID对应的列表不存在，就新建一个空列表，再把当前文件加进去
//            第一个参数 record.getParentId()：Map 的 Key，也就是父目录 ID，用来分组。
//            第二个参数 key -> new ArrayList<>()：「缺省值生成函数」—— 当 Key 不存在时，用这个逻辑生成默认的 Value。
//            这里的 key 是形参，就是前面的父 ID，这个场景里没用到它，只是单纯新建一个空列表。
//            computeIfAbsent 执行完会返回对应的 List（要么已有的，要么新建的），紧接着调用 .add(record)，就把当前这条文件记录，加到这个父目录的子列表里。
            childrenMap.computeIfAbsent(record.getParentId(), key -> new ArrayList<>()).add(record);
        }

        // ========== 第五步：BFS广度优先遍历，收集所有子孙文件ID ==========
        // 用队列实现层序遍历：从选中的顶层节点开始，一层层往下找所有子节点、孙子节点...
        // 为什么不用递归？文件夹层级很深时，递归会栈溢出；队列循环更稳定，不会有栈溢出问题
        ArrayDeque<Long> queue = new ArrayDeque<>(operationIdSet);

        while (!queue.isEmpty()) {
            // 取出队首的父节点ID
            Long parentId = queue.poll();

            // 从索引里拿出这个父节点的所有直接子文件
            List<UserFileDO> children = childrenMap.get(parentId);
            // 没有子节点，跳过
            if (CollectionUtils.isEmpty(children)) {
                continue;
            }

            // 遍历所有子节点
            for (UserFileDO child : children) {
                // Set.add() 返回boolean：true=新增成功（之前没有这个ID）
                // 新增成功就把它加入队列，继续找它的子节点；已经存在就跳过，避免重复处理
                if (operationIdSet.add(child.getId())) {
                    queue.offer(child.getId());
                }
            }
        }

        // ========== 第六步：转成List返回 ==========
        return new ArrayList<>(operationIdSet);
    }

}
