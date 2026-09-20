# 踩坑记录

面试时高频问题之一是「你踩过最大的坑是什么」。
这份文件把项目推进过程中真实遇到的坑记下来,配上面向面试的表达。

---

## 001 · Docker Desktop 启动报 WSL 错误

**现象**

```
DockerDesktop/Wsl/ExecError: wsl.exe --version: exit status 1
```

**排查过程**

1. 执行 `wsl --version`,输出的是 WSL 帮助信息、退出码 1 —— 说明系统里只有
   Windows 自带的旧版 `wsl.exe` 存根,它根本不认识 `--version` 参数。
2. 检查系统服务,发现 `LxssManager` / `WSLService` 都不存在 → WSL 功能没启用。
3. 检查 `C:\Program Files\WSL\wsl.exe` 不存在 → 新版 WSL 从没装过。

**根因**

Windows 11 家庭版没有 Hyper-V,Docker Desktop 只能用 WSL2 后端,
而机器上根本没装 WSL。

**解决过程中的第二个坑**

`wsl --install --no-distribution` 报 `Wsl/CallMsi/Install/0x80190193`。
`0x80190193` 是 HTTP 403,微软的下载服务器拒绝了请求。

绕开办法:直接从微软 GitHub 官方仓库下载对应版本的 MSI 手工安装。

**第三个坑**

手工装 MSI 还报 `Could not write value to key \SOFTWARE\Classes\Drive\shell\WSL`。
查该注册表键的 ACL,发现 Administrators 和 SYSTEM 都只有 ReadKey 权限
(被系统优化工具锁过),而 MSI 是以 SYSTEM 身份运行的,写不进去。

解决:把该键的 FullControl 权限授予 Administrators 和 SYSTEM。

**面试可以怎么讲**

> 环境问题是靠逐层排除定位的:先确认报错来自哪一层(Docker → WSL → 系统功能 → 注册表),
> 再逐层验证。比如 `wsl --version` 返回帮助文本而不是版本号,就说明二进制本身是旧的存根,
> 而不是 WSL 装坏了。

---

## 002 · IDEA 报「java: 错误: 不支持发行版本 5」

**现象**

IDEA 里编译报错:`java: 错误: 不支持发行版本 5`,但命令行 `mvn compile` 完全正常。

**判断方法(重要)**

命令行能编译、IDE 报错 → 说明 **代码和 pom 没问题,问题在 IDE 配置**。
这一步判断能省掉大量无效排查。

**排查过程**

1. 检查 `.idea/misc.xml`,项目和语言级别都写着 `JDK_17`,`project-jdk-name="17"` —— 配置看起来是对的。
2. 检查 IDEA 的 JDK 表(`jdk.table.xml`),名字为 `17` 的 SDK 确实存在,指向 `D:/develop/JDK17` —— 没问题。
3. 关键线索:`.idea` 目录下**没有 `modules.xml`**,也没有 `.idea/modules/`。
   说明这个项目**压根没被导入成 Maven 项目**,IDEA 没有为它创建模块。
4. 查看 `.idea/workspace.xml`,发现:

```xml
<MavenGeneralSettings>
  <option name="mavenHomeTypeForPersistence" value="WRAPPER" />
</MavenGeneralSettings>
```

IDEA 被配置成使用 **Maven Wrapper(`mvnw`)**,但这个项目里没有 `mvnw` 文件。

**根因**

IDEA 用 Wrapper 模式导入 → 找不到 `mvnw` → Maven 导入失败 → 项目没有模块 →
编译器退回默认语言级别 5(Java 1.5)→ JDK 17 的 javac 不支持 `-source 5` → 报错。

**解决**

用 `mvn wrapper:wrapper` 给项目补上 `mvnw` / `mvnw.cmd` / `.mvn/wrapper/`,
并把 `distributionUrl` 从 Maven 中央仓库改成阿里云镜像(9MB 的 Maven 发行包,
走中央仓库在国内会非常慢)。然后在 IDEA 里重新导入 Maven 项目。

**改完后又冒出来的第四个坑**

改完 `maven-wrapper.properties` 后执行 `mvnw.cmd -v`,报:

```
cannot read distributionUrl property in .../maven-wrapper.properties
```

原因是那个文件里加了中文注释。`mvnw.cmd` 是通过 `cmd.exe` 调起 PowerShell 的,
PowerShell 会用系统默认编码(中文系统是 GBK)去读文件,
而文件是 UTF-8 无 BOM,中文注释被解码成乱码,`ConvertFrom-StringData` 就解析不出键值对了。

**这也符合 Java 的规范:`.properties` 文件本来就应当是纯 ASCII。**
所有中文说明都应当挪到 `.md` 文档里,不要写进 properties。

**顺带得到的经验**

Maven Wrapper 的作用是让项目自带构建工具版本,别人 clone 下来不用装 Maven 就能构建。
在 Docker 构建镜像时也常用 `./mvnw` 代替 `mvn`,保证构建环境一致(见第 24 项)。

**面试可以怎么讲**

> IDE 报错但命令行正常,基本可以断定是 IDE 的项目模型没建好,而不是代码问题。
> 我当时通过 `.idea` 目录下缺少 `modules.xml` 判断出 Maven 项目根本没导入成功,
> 再顺着 `workspace.xml` 里的 `MavenGeneralSettings` 找到它用的是 Wrapper 模式,
> 而项目里恰好缺 `mvnw`,闭环就对上了。

---

## 003 · Docker 构建时拉不到基础镜像(国内网络)

**现象**

`docker compose up -d --build` 报:

```
ERROR [internal] load metadata for docker.io/library/eclipse-temurin:17-jre
failed to fetch anonymous token:
Get "https://auth.docker.io/token?...": dial tcp ...: connectex:
A connection attempt failed because the connected party did not properly respond
```

**原因**

`auth.docker.io` 是 Docker Hub 的认证服务。国内网络访问它经常超时,
这不是配置写错,也不是 Dockerfile 有问题 —— 是**镜像仓库拉不通**。

三个中间件的镜像之前已经拉过,所以它们能正常起;而
`maven` 和 `eclipse-temurin` 这两个基础镜像是第一次拉,于是就卡住了。

**三种解法(按推荐顺序)**

**方法一:配置镜像加速器**

Docker Desktop → `Settings` → `Docker Engine`,在 JSON 里加:

```json
{
  "registry-mirrors": ["https://你的专属地址.mirror.aliyuncs.com"]
}
```

专属地址在阿里云「容器镜像服务 → 镜像工具 → 镜像加速器」页面获取
(需要阿里云账号,和千帆是同一套账号体系)。

改完点 `Apply & restart`,Docker 会重启。

> 注意:2024 年之后很多公共的免费镜像加速器都关停了,
> 网上搜到的地址大多已经失效,用之前先试一下。

**方法二:换基础镜像来源(本项目的 Dockerfile 已支持)**

在项目根目录的 `.env` 里加一行:

```
DOCKER_REGISTRY=public.ecr.aws/docker/library
```

然后重新构建。AWS 的 ECR Public 镜像了 Docker Hub 的官方镜像,
国内一般可以直连。

**方法三:应用不容器化,只容器化中间件**

```bash
docker compose up -d postgres elasticsearch redis
```

应用在 IDEA 里跑(`--spring.profiles.active=local`)。

**这个方案对面试演示完全够用** —— 中间件容器化已经解决了"换台机器跑不起来"的问题,
应用本身用 IDEA 跑反而更方便调试。容器化应用属于锦上添花。

**面试可以怎么讲**

> 部署本身没花多少时间,卡住的是网络。这种事排查起来不难 ——
> 关键是别一上来就怀疑自己的 Dockerfile 写错了,
> 先看清楚报错到底是在哪一步:连接超时是网络层的问题,
> 和镜像构建的逻辑没关系。

---

## 004 · Docker Desktop 起不来,前端报「Failed to fetch」

**现象**

前端页面还开着(旧标签页),但拖文件上传时报 `Failed to fetch`。

**排查过程**

1. `Failed to fetch` 是浏览器在网络层报的错 —— **不是业务错误**,
   说明请求根本没发出去或没收到响应。
2. 检查端口:`8080`(后端)和 `5173`(前端)都没在监听。
   页面上看到的是**缓存的旧界面**,界面还在不代表服务还活着。
3. 检查 Docker:`Docker Desktop` 进程存在,但窗口上弹着
   「An unexpected error occurred」。

**根因**

Docker Desktop 启动时报:

```
initializing Ingest server: listening on unix:///.../Docker/run/sailor-ingest.sock:
rename ...sailor-ingest.sock ...sailor-ingest.sock.stale:
The file cannot be accessed by the system.
```

**这是 Windows 上 Docker Desktop 的一个经典问题**:

- Docker 内部大量使用 **Unix domain socket**(`*.sock`),
  启动时会把旧的 socket 轮换成 `.stale`
- 如果上一次是**非正常退出**(强杀进程、断电、蓝屏),
  残留的 `.stale` 文件会处于「无法访问」状态 —— 删不掉、改不了名
- 于是下一次启动时轮换失败,整个启动流程直接崩掉

本例中残留文件的时间戳是前一天 16:44,说明**故障在那时就埋下了**,
只是当时 Docker 还在跑所以没暴露。

**修复**

关键是:**这些文件动不了,但它们的父目录可以改名。**

```powershell
# 1. 彻底结束 Docker 进程
Get-Process | Where-Object { $_.ProcessName -match 'docker|vpnkit' } | Stop-Process -Force

# 2. 把整个目录改名,Docker 下次启动会重建一个干净的
Rename-Item 'C:\Users\xunqian\AppData\Local\Docker\run' 'run.bak'
Rename-Item 'C:\Users\xunqian\AppData\Local\docker-secrets-engine' 'docker-secrets-engine.bak'

# 3. 重新启动 Docker Desktop
```

**注意有两个目录都要处理** —— 修好 `Docker\run` 之后,
下一次启动会卡在 `docker-secrets-engine`,只是报错里的路径换了。
**看日志确认到底卡在哪个目录**,不要只修一个。

**预防**

- 关闭 Docker Desktop 用托盘图标里的 **Quit**,不要用任务管理器强杀
- 项目根目录提供了 `start.cmd`,会先启动 Docker、等所有依赖就绪再开前端 ——
  避免"服务没起来但页面还能打开"造成的误判

**面试可以怎么讲**

> 这个问题的价值不在结论,在于**排查路径**:
> 浏览器报 `Failed to fetch` → 先确认是网络层而不是业务层
> → 查端口发现服务根本没在跑 → 顺藤摸瓜到 Docker
> → 看日志定位到具体是哪一步失败。
>
> 另外一条经验:**页面上能看到界面,不代表服务还活着** ——
> 浏览器缓存会让你误以为一切正常。
