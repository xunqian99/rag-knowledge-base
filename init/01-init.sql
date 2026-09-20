-- ============================================================
-- PostgreSQL 初始化脚本
--
-- 执行时机:仅在「数据卷为空 + 容器首次启动」时自动执行一次。
-- 后期修改本文件不会自动生效,需要 docker compose down -v 清空数据卷后重启。
--
-- 注意:建业务表(文档表 / 分块表 / 会话表)是第 3 项的任务,
-- 这里只负责让数据库具备「存向量」的能力。
-- ============================================================

-- 启用 pgvector 扩展。
-- 装上它之后,PostgreSQL 就有了 vector 数据类型和 <=> 这类距离运算符,
-- 可以直接在 SQL 里做「找与给定向量最相似的 N 条」。
CREATE EXTENSION IF NOT EXISTS vector;

-- 顺手打印一下扩展版本,方便确认真的装上了。
SELECT extname AS "扩展名", extversion AS "版本"
FROM pg_extension
WHERE extname = 'vector';
