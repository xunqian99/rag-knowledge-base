package cn.ragserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 项目启动类。
 *
 * @SpringBootApplication 是一个组合注解,它同时做了三件事:
 *   1. @SpringBootConfiguration —— 声明这是一个配置类
 *   2. @EnableAutoConfiguration —— 开启自动配置(靠它,加了依赖+配置就能连上数据库)
 *   3. @ComponentScan        —— 扫描本包及子包下的 @Component/@Service/@RestController
 *
 * 注意最后一条:这个类必须放在最外层的包(cn.ragserver)下,
 * 否则建在子包里的 Controller、Service 不会被扫描到,接口会 404。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RagServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagServerApplication.class, args);
    }

}
