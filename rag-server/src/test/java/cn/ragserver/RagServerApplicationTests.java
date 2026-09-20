package cn.ragserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载测试:启动完整的 Spring 容器,验证所有 Bean 能正常装配。
 *
 * 这比"直接跑 main 方法"更严格——它会真实地初始化数据源、
 * 连接池、Redis 客户端。所以跑这个测试的前提是 Docker 里的服务已经起来了。
 */
@SpringBootTest
class RagServerApplicationTests {

    @Test
    void contextLoads() {
    }

}
