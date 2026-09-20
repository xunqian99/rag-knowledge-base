package cn.ragserver.health;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * PostgreSQL 探测器。
 */
@Component
public class DatabaseChecker implements DependencyChecker {

    private final DataSource dataSource;

    public DatabaseChecker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public void check() throws Exception {
        // 注意这里用的是注入进来的 DataSource(也就是连接池),
        // 而不是自己 DriverManager.getConnection()。
        //
        // 原因:健康检查要探测的是「应用真正用的那条链路」。
        // 自己新建连接会绕开连接池,可能连接池已经打满、借不到连接了,
        // 而自检还显示一切正常,这就失去意义了。
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select 1")) {
            // 必须真的读一下结果,只执行 executeQuery 不消费结果集
            // 在某些驱动下是懒执行的,等于没测。
            rs.next();
        }
    }
}
