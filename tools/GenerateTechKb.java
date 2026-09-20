import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 生成一套「技术团队知识库」素材,用于导入测试。
 *
 * 设计目标不是"内容多",而是**让检索有难度**:
 *   - 埋相似但不同的数字(支付超时 30s / 退款超时 60s),考检索精度
 *   - 埋只差一位的错误码(3001 / 3002),考关键词匹配
 *   - 埋跨文档的关联(新人指南引用接口规范),考多文档召回
 */
public class GenerateTechKb {

    private static final String OUT_DIR = "C:/Users/xunqian/Desktop/1/sample-data/tech-kb";

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(OUT_DIR));
        writePdf("支付网关接口规范.pdf", PAYMENT_API);
        writeDocx("线上故障排查手册.docx", TROUBLESHOOT);
        writeTxt("数据库表结构说明.txt", DATABASE);
        writeXlsx("服务与负责人清单.xlsx", SERVICES);
        writeTxt("发布流程与回滚预案.txt", RELEASE);
        writePdf("新人上手指南.pdf", ONBOARDING);
    }

    // ------------------------------------------------------------------

    private static final List<String> PAYMENT_API = List.of(
            "支付网关接口规范 v2.3",
            "",
            "第一章 接口概览",
            "",
            "1.1 支付网关对外提供三个核心接口:创建支付(pay/create)、查询订单(pay/query)、发起退款(pay/refund)。",
            "1.2 所有接口均为 HTTP POST,请求体与响应体都是 JSON,字符集固定 UTF-8。",
            "1.3 生产环境网关地址为 https://gw.pay.internal/v2,测试环境为 https://gw.pay.test/v2。",
            "1.4 网关对单个商户的限流为每秒 200 次,超出返回 429。",
            "",
            "第二章 超时与重试",
            "",
            "2.1 创建支付接口的服务端超时时间为 30 秒。",
            "2.2 发起退款接口的服务端超时时间为 60 秒,因为退款需要与银行侧对账。",
            "2.3 查询订单接口的超时时间为 5 秒。",
            "2.4 客户端重试策略:创建支付最多重试 2 次,退款接口不建议重试,查询接口可重试 3 次。",
            "2.5 重试必须使用相同的商户订单号,网关会做幂等处理。",
            "",
            "第三章 签名与鉴权",
            "",
            "3.1 请求头必须携带 X-Merchant-Id 与 X-Signature 两个字段。",
            "3.2 签名算法为 HMAC-SHA256,签名串的拼接顺序是:时间戳 + 随机串 + 请求体。",
            "3.3 时间戳与服务器时间相差超过 5 分钟,请求会被拒绝,错误码 1002。",
            "3.4 商户密钥每 180 天必须轮换一次,轮换期间新旧密钥同时有效 24 小时。",
            "",
            "第四章 错误码",
            "",
            "4.1 1001 参数缺失,1002 签名校验失败,1003 商户不存在。",
            "4.2 3001 表示账户余额不足,3002 表示单笔交易额度超限,3003 表示单日累计额度超限。",
            "4.3 5001 表示银行侧超时,此时订单状态未知,必须通过查询订单接口确认最终结果。",
            "4.4 遇到 5001 时禁止直接重新发起支付,否则可能造成重复扣款。",
            "",
            "第五章 对账",
            "",
            "5.1 网关每天凌晨 2:00 生成前一日对账文件,保存于商户后台。",
            "5.2 对账文件格式为 CSV,字段依次是:商户订单号、网关流水号、金额、状态、完成时间。",
            "5.3 商户需在 T+2 日内完成对账,超期未对账的差异将不再受理。");

    private static final List<String> TROUBLESHOOT = List.of(
            "线上故障排查手册",
            "",
            "第一章 排查原则",
            "",
            "1.1 先止损,再定位。影响用户的问题优先切流或回滚,不要在现场分析根因。",
            "1.2 所有操作必须记录在值班群里,包括执行时间、执行人、执行内容。",
            "1.3 禁止在生产环境直接改配置或连数据库改数据,必须走发布流程。",
            "",
            "第二章 常见故障与处理",
            "",
            "2.1 接口大面积超时",
            "先看网关的错误率面板,区分是自身服务问题还是下游依赖问题。若是下游问题,先启用降级开关。",
            "2.2 数据库连接池打满",
            "现象是接口大量报获取连接超时。应急手段是先把非核心接口的流量切走,再排查是否存在慢查询。",
            "2.3 消息堆积",
            "先确认消费者是否还在运行,再看消费速率与生产速率的差值。堆积超过 10 万条需要告警升级。",
            "2.4 缓存的雪崩与击穿",
            "大量 key 同时过期会造成数据库压力骤增。应急时可以对热点 key 做临时续期。",
            "",
            "第三章 错误码对照",
            "",
            "3.1 网关侧:429 表示限流,502 表示后端不可用,504 表示后端超时。",
            "3.2 支付网关业务错误码 3001 是余额不足,3002 是额度超限 —— 这两个码长得很像,看日志时不要看错。",
            "3.3 内部服务统一用 5 位错误码,前两位表示服务编号,后三位表示具体原因。",
            "",
            "第四章 日志与链路",
            "",
            "4.1 每个请求都会带一个 traceId,排查时用它串联所有服务的日志。",
            "4.2 日志保留期为 30 天,超过 30 天的日志需要从归档存储恢复。",
            "4.3 慢查询日志阈值设为 1 秒,超过阈值的 SQL 会单独记录。",
            "",
            "第五章 升级与上报",
            "",
            "5.1 故障持续超过 15 分钟需上报技术负责人。",
            "5.2 影响核心交易链路超过 30 分钟需上报至 CTO。",
            "5.3 故障恢复后 3 个工作日内必须产出复盘文档。");

    private static final List<String> DATABASE = List.of(
            "核心数据库表结构说明",
            "",
            "一、库与实例",
            "",
            "生产库实例为 pg-prod-01,从库两个,分别承担只读查询与报表导出。",
            "测试库实例为 pg-test-01,单实例,数据每周从生产脱敏导入一次。",
            "连接池上限:生产 200,测试 50。",
            "",
            "二、交易相关表",
            "",
            "t_order 订单主表,主键 order_id,关键字段 user_id、amount、status、created_at。",
            "  status 取值:0 待支付、1 已支付、2 已退款、3 已关闭。",
            "t_order_item 订单明细表,一个订单对应多行,通过 order_id 关联。",
            "t_payment 支付流水表,记录每一次支付尝试,同一个 order_id 可能有多行。",
            "",
            "三、日志类表",
            "",
            "t_order_log 记录订单状态变更,只追加不修改。",
            "t_payment_log 记录支付接口的请求与响应,单表数据量最大,已按月份分区。",
            "t_audit_log 记录后台管理操作,保留期 2 年。",
            "注意:t_payment_log 和 t_payment 名字接近,但前者是流水日志、后者是业务数据,不要混用。",
            "",
            "四、索引约定",
            "",
            "所有对外查询字段必须建索引,查询必须走索引。",
            "联合索引遵循最左前缀原则,例如 (user_id, created_at) 可以用 user_id 单独查询,但不能用 created_at 单独查。",
            "单表索引数量不超过 6 个。",
            "",
            "五、数据量与归档",
            "",
            "t_order 当前约 8000 万行,按 created_at 做范围归档,保留最近 24 个月。",
            "t_payment_log 约 12 亿行,按月份分区,保留 12 个月。",
            "归档数据存放在对象存储,需要时可恢复到归档库。");

    private static final List<List<String>> SERVICES = List.of(
            List.of("服务名", "中文名", "负责人", "SLA", "值班群", "实例数(生产)"),
            List.of("pay-gateway", "支付网关", "陈立", "99.95%", "支付值班群", "12"),
            List.of("pay-gateway-admin", "支付网关管理后台", "陈立", "99.90%", "支付值班群", "3"),
            List.of("order-center", "订单中心", "李欣然", "99.95%", "交易值班群", "16"),
            List.of("settle-service", "结算服务", "王海涛", "99.90%", "交易值班群", "8"),
            List.of("risk-engine", "风控引擎", "赵敏", "99.99%", "风控值班群", "20"),
            List.of("notify-service", "消息通知服务", "周晓阳", "99.50%", "平台值班群", "6"),
            List.of("user-center", "用户中心", "孙博", "99.95%", "平台值班群", "10"),
            List.of("report-service", "报表服务", "吴倩", "99.00%", "平台值班群", "4"));

    private static final List<String> RELEASE = List.of(
            "发布流程与回滚预案",
            "",
            "第一章 发布窗口",
            "",
            "1.1 常规发布窗口为每周二、周四的 14:00 至 17:00。",
            "1.2 周五至周日以及法定节假日前后一个工作日禁止发布。",
            "1.3 紧急修复可以走绿色通道,但需技术负责人和产品负责人双方确认。",
            "",
            "第二章 发布步骤",
            "",
            "2.1 先在测试环境验证,测试环境共 3 个实例,数据来自生产脱敏。",
            "2.2 生产环境共 12 个实例,采用分批灰度:先发 2 个,观察 10 分钟。",
            "2.3 观察指标包括错误率、P99 延迟、CPU 使用率、慢查询数量。",
            "2.4 灰度通过后再发剩余 10 个实例,每批发完观察 5 分钟。",
            "2.5 全部发完后持续观察 30 分钟方可结束发布。",
            "",
            "第三章 回滚条件",
            "",
            "3.1 错误率超过基线 3 倍,立即回滚。",
            "3.2 P99 延迟超过基线 2 倍且持续 5 分钟,立即回滚。",
            "3.3 出现资损(金额计算错误)不管比例多少,立即回滚并上报。",
            "3.4 回滚以实例为单位,回滚顺序与发布顺序相反。",
            "",
            "第四章 回滚预案",
            "",
            "4.1 代码回滚使用上一版本的镜像,切换时间约 2 分钟。",
            "4.2 数据库变更是不可回滚的,所以发布前必须确认新增字段可空、新表不影响老代码。",
            "4.3 涉及数据订正的发布必须提前准备好反向订正脚本。",
            "4.4 回滚之后必须在值班群同步,并说明回滚原因与影响范围。",
            "",
            "第五章 发布后检查清单",
            "",
            "5.1 确认所有实例的版本号一致。",
            "5.2 确认核心接口的调用量恢复到发布前水平。",
            "5.3 确认没有新增的错误日志类型。",
            "5.4 更新发布记录,注明版本号、发布时间、参与人。");

    private static final List<String> ONBOARDING = List.of(
            "新同学上手指南",
            "",
            "第一章 第一天做什么",
            "",
            "1.1 上午 10:00 到大堂前台领工牌,IT 会同步发放笔记本电脑。",
            "1.2 用企业邮箱激活域账号,初始密码由 IT 单独发送。",
            "1.3 加入团队的企业微信群,修改群昵称为「姓名-岗位」。",
            "1.4 找导师要一份《本周学习计划》,他会带你过一遍系统架构。",
            "",
            "第二章 开发环境搭建",
            "",
            "2.1 安装 JDK 17、Maven、Docker Desktop 与 IntelliJ IDEA。",
            "2.2 从代码仓库克隆主工程,首次构建约需 10 分钟。",
            "2.3 本地依赖用 Docker Compose 启动,数据库和缓存的连接信息见项目的 README。",
            "2.4 启动成功后访问健康检查接口,所有依赖都应该是 UP。",
            "2.5 如果构建卡在拉取基础镜像,大概率是网络问题,可以配置镜像加速。",
            "",
            "第三章 必读的三份文档",
            "",
            "3.1 《支付网关接口规范》—— 了解对外接口的约定,重点是签名和错误码。",
            "3.2 《数据库表结构说明》—— 了解核心表和索引约定,避免写出全表扫描的 SQL。",
            "3.3 《发布流程与回滚预案》—— 了解发布窗口和回滚条件,这是上线前的必修课。",
            "",
            "第四章 常用操作",
            "",
            "4.1 查看服务日志:在日志平台按 traceId 搜索。",
            "4.2 查看线上配置:通过配置中心,只读权限默认开通,写权限需要申请。",
            "4.3 申请数据库权限:填写权限申请单,由导师和 DBA 双重审批。",
            "4.4 提交代码:必须关联需求单号,提交信息写清楚改了什么、为什么改。",
            "",
            "第五章 常见坑",
            "",
            "5.1 本地连测试库时不要执行任何写操作,测试库的数据每周会被覆盖。",
            "5.2 调试支付相关功能时,一定要用测试商户号,不要用生产商户号。",
            "5.3 遇到报错先看 traceId 对应的完整链路,不要只看自己服务的日志。",
            "5.4 有问题先在团队群里问,不要自己憋着 —— 新人卡住是正常的,憋着才不正常。");

    // ------------------------------------------------------------------

    private static void writeTxt(String name, List<String> lines) throws Exception {
        Path target = Paths.get(OUT_DIR, name);
        Files.write(target, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        System.out.printf("  已生成 TXT : %s (%d 字节)%n", name, Files.size(target));
    }

    private static void writeDocx(String name, List<String> lines) throws Exception {
        Path target = Paths.get(OUT_DIR, name);
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(target.toFile())) {
            for (String line : lines) {
                XWPFParagraph paragraph = doc.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line);
                run.setFontSize(line.isBlank() ? 6 : 11);
                if (line.startsWith("第")) {
                    run.setBold(true);
                }
            }
            doc.write(out);
        }
        System.out.printf("  已生成 DOCX: %s (%d 字节)%n", name, Files.size(target));
    }

    private static void writeXlsx(String name, List<List<String>> rows) throws Exception {
        Path target = Paths.get(OUT_DIR, name);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("服务清单");
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    row.createCell(c).setCellValue(rows.get(r).get(c));
                }
            }
            for (int c = 0; c < 6; c++) {
                sheet.autoSizeColumn(c);
            }
            try (FileOutputStream out = new FileOutputStream(target.toFile())) {
                workbook.write(out);
            }
        }
        System.out.printf("  已生成 XLSX: %s (%d 字节)%n", name, Files.size(target));
    }

    private static void writePdf(String name, List<String> lines) throws Exception {
        Path target = Paths.get(OUT_DIR, name);
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font = PDType0Font.load(doc, new File("C:/Windows/Fonts/simhei.ttf"));
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            cs.beginText();
            cs.setFont(font, 10.5f);
            cs.setLeading(16f);
            cs.newLineAtOffset(56, 780);
            float y = 780;
            for (String line : lines) {
                if (y < 60) {
                    cs.endText();
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    cs.beginText();
                    cs.setFont(font, 10.5f);
                    cs.setLeading(16f);
                    cs.newLineAtOffset(56, 780);
                    y = 780;
                }
                cs.showText(line.isEmpty() ? " " : line);
                cs.newLine();
                y -= 16;
            }
            cs.endText();
            cs.close();
            doc.save(target.toFile());
        }
        System.out.printf("  已生成 PDF : %s (%d 字节)%n", name, Files.size(target));
    }
}
