-- ============================================================
-- V1 · 初始化表结构
--
-- 这是 Flyway 管理的第一个迁移脚本,应用启动时自动执行。
-- 命名规则:V<版本号>__<描述>.sql —— 版本号后面是两个下划线,不是一个。
--
-- 【重要规则】表结构只由本目录下的脚本定义,不要手工连数据库改表。
-- 需要改结构时,新增 V2__xxx.sql,而不是回头修改本文件。
-- 因为 V1 已经在数据库里执行过了,Flyway 不会再跑它,改了也不生效,
-- 反而会让 validate-on-migrate 校验失败。
-- ============================================================


-- ------------------------------------------------------------
-- pgvector 扩展:让 PostgreSQL 支持 vector 类型和向量距离运算
--
-- docker-compose 里的 init 脚本已经建过一次,这里再写一遍是为了让本文件
-- 自包含 —— 换一台机器、或者手工建库时也能一次跑通。
-- IF NOT EXISTS 保证重复执行不会报错。
-- ------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;


-- ------------------------------------------------------------
-- 文档表:一个上传的文件对应一行
-- ------------------------------------------------------------
CREATE TABLE document (
    id           BIGSERIAL    PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,
    file_type    VARCHAR(20)  NOT NULL,
    -- 文件大小,单位字节。用 BIGINT 而不是 INT,
    -- INT 上限约 2GB,单个大 PDF 就可能撑爆。
    file_size    BIGINT       NOT NULL,
    -- 原始文件在本地的相对路径(第 5 项决定具体规则)
    storage_path VARCHAR(500) NOT NULL,
    -- 处理状态:PENDING 待处理 / INDEXING 处理中 / INDEXED 已完成 / FAILED 失败
    -- 这里没有加 CHECK 约束,状态枚举交给应用层控制,
    -- 好处是以后加新状态不用改表结构。
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    chunk_count  INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);


-- ------------------------------------------------------------
-- 分块表:整个项目的核心数据
-- 一个文档被切成 N 块,每块一行,每行带一个向量
-- ------------------------------------------------------------
CREATE TABLE document_chunk (
    id          BIGSERIAL   PRIMARY KEY,
    -- ON DELETE CASCADE:删掉文档时,它的所有分块自动跟着删。
    -- 不用在代码里写两遍删除逻辑,也不会留下孤儿数据。
    document_id BIGINT      NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    -- 这块在原文里是第几块,从 0 开始
    chunk_index INTEGER     NOT NULL,
    -- 分块后的文本内容
    content     TEXT        NOT NULL,
    -- 向量列。1024 是维度,由 embedding 模型决定。
    -- 允许为 NULL:入库时先写文本,向量是异步生成后才回填的(第 7 项)。
    embedding   vector(1024),
    -- 附加信息:页码、所属标题路径等,第 10 项分块策略会往里写
    metadata    JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 同一文档内块序号不能重复。
    -- 第 12 项「重新索引」时会先删旧块再插新块,这个约束能防止重复插入。
    CONSTRAINT uk_chunk_document_index UNIQUE (document_id, chunk_index)
);

-- 按文档查分块(第 11 项删除文档、第 12 项重新索引都要用)
CREATE INDEX idx_chunk_document_id ON document_chunk (document_id);

-- 向量索引。HNSW 是本项目选定的近似最近邻索引,
-- 相比 IVFFlat 不需要预先「训练」聚类,对数据量小的场景更友好。
--
-- vector_cosine_ops 表示用余弦距离。
-- 余弦只看方向、不看向量长度,是文本 embedding 的常规选择。
-- m 和 ef_construction 用 PostgreSQL 的默认值(16 / 64),
-- 数据量大了以后再回来调这个索引。
CREATE INDEX idx_chunk_embedding_hnsw
    ON document_chunk USING hnsw (embedding vector_cosine_ops);


-- ------------------------------------------------------------
-- 会话表:一次对话一行
-- ------------------------------------------------------------
CREATE TABLE chat_session (
    id         BIGSERIAL    PRIMARY KEY,
    -- 会话标题,可以取第一个问题自动生成
    title      VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);


-- ------------------------------------------------------------
-- 消息表:一条问答一行
--
-- 为什么把会话拆成两张表:引用来源、耗时这些信息属于「某一条回答」,
-- 不属于「整个会话」。拆开之后,第 8 项做多轮对话、第 21 项记首字延迟
-- 都不用再改表结构。
-- ------------------------------------------------------------
CREATE TABLE chat_message (
    id              BIGSERIAL   PRIMARY KEY,
    session_id      BIGINT      NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    -- USER 用户提问 / ASSISTANT 模型回答
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    -- 引用溯源:这条回答引用了哪几个分块。
    -- BIGINT[] 是 PostgreSQL 原生数组类型,存 ID 列表比建关联表简单。
    cited_chunk_ids BIGINT[],
    -- 这一轮的耗时,单位毫秒。第 21 项统计首字延迟、第 23 项做性能优化时用。
    latency_ms      INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_session_id ON chat_message (session_id);
