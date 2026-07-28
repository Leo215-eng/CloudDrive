-- 已有数据库只执行一次；全新数据库已包含在 DDL_network_disk.sql 中。
ALTER TABLE `user_file`
    ADD INDEX `idx_user_modified` (`user_id`, `gmt_modified`);
