# Gravity Flip —— Minecraft 1.20.1 Forge 模组

> 受伤后重力随机翻转到 6 个方向之一，并一直保持。只作用于玩家。
> 代码已完成；构建受本机网络影响，见文末「已知问题」。

## 玩法

- 玩家受到任意伤害（`LivingHurtEvent`，`amount > 0`）时，随机换到**另一个**方向：
  下 / 上 / 北 / 南 / 西 / 东（排除当前方向，保证每次都有变化）。
- 方向记录在玩家 `persistentData`（随存档保存），并同步给客户端；客户端与服务端用同一套速度补偿计算，移动不会抖。
- 8 tick 内多次受伤只算一次翻转（避免火焰 + 近战的同一刻翻两次）。
- 死亡重生 / 换维度重置为正常重力。
- 上升到 y≥318 自动恢复正常重力，避免永久卡在世界上限。
- 水下、岩浆、创造飞行、鞘翅滑翔、乘坐载具时不施加自定义重力。
- 跳跃逻辑未改：任意重力下按空格仍是原版向上跳。

## 物理实现

不改 `travel`、不用 Mixin，只在 `PlayerTickEvent` 的 END 阶段修正速度：

```
原版空中每 tick: v.y -= 0.08
本模组: 若在空中先 v.y += 0.08 抵消，再 v += 方向 * 0.08
```

所以「重力向下」等价于原版；「向上」时上升速度与原版下落速度一致（终端速度约 4 格/tick）。

## 源码结构

```
src/main/java/com/gravityflip/
├─ GravityFlipMod.java      入口，注册事件与 SimpleChannel
├─ GravityDirection.java    6 个方向（id / 单位向量 / 中文标签）
├─ PlayerGravity.java       方向存取（服务端 persistentData / 客户端内存表）
├─ GravityHandler.java      受伤翻转、每 tick 重力、登录/重生同步
└─ net/GravityPacket.java   服务端 → 客户端同步包
src/main/resources/
├─ META-INF/mods.toml
└─ pack.mcmeta
```

## 联机（多人 / 专用服务器）

模组按**服务端权威**设计，联机可用：

- **服务端**：`LivingHurtEvent` 命中某个 `ServerPlayer` 时随机方向，写进该玩家的
  `persistentData` 并下发给对应客户端；`PlayerTickEvent` 在服务端也为每个玩家施加定向重力。
- **客户端**：只对**本地玩家**施加同样的速度修正（`ClientGravityHooks.isLocalPlayer`），
  其他玩家由服务端位置包驱动，不会双算。
- **同步**：登录时下发一次（`PlayerLoggedInEvent`），之后每次翻转单独发包；
  死亡重生 / 换维度重置为正常重力。
- **专用服务器安全**：`Minecraft` 只出现在 `com.gravityflip.client.ClientGravityHooks`
  这个 `@OnlyIn(Dist.CLIENT)` 类里，公共代码只在 `level().isClientSide` 分支调用它，
  服务器不会加载该类（否则会 `NoClassDefFoundError`）。

**部署要求**：客户端和服务端**都要装**这个 mod（网络通道版本不一致会被拒绝连接）。

**联机验证清单**（未实测）：
1. 单机存档先验证基础效果；
2. 局域网 / 专用服务器上让两名玩家同时受伤，确认各自方向独立、互不影响；
3. 第三名玩家看到的另外两人位置平滑（不抖动）；
4. 玩家死亡重生后重力恢复为「下」；
5. 一名玩家带副作用退出再进，确认方向从存档恢复。

## 构建

```powershell
D:\deepseek\gravity-flip\build.ps1
```

脚本会设置 `JAVA_HOME`（`D:\deepseek\tools\jdk-17.0.21+9`）、`GRADLE_USER_HOME`
（`D:\deepseek\tools\gradle-home`），然后跑 `gradle build`。
产物：`build\libs\gravityflip-1.0.0.jar`。

工具链已随工程放在 `D:\deepseek\tools`：
- JDK 17.0.21（Temurin，清华镜像下载）
- Gradle 8.1.1（腾讯镜像下载）
- Gradle 缓存（含 MC 客户端/服务端、映射表、全部 88 个依赖库，已按官方 sha1 校验）

## 已知问题（本机构建）

ForgeGradle 的 `compileJava` 在解析 `forge:1.20.1-47.2.0_mapped_official_1.20.1`
时会向 `maven.minecraftforge.net`（Cloudflare）发探测请求，**本机到该 CDN 的连接会随机挂住不返回**，
而这段下载逻辑没有超时，所以构建卡死。已经确认：

- `curl` 访问同一地址正常（2–7 秒返回，200）；
- 用「指向关闭端口」的代理可以让它快速失败，证明卡点就是这一步的网络请求；
- 清掉 Gradle 残留锁后，MCP 的 downloadManifest → rename 全部步骤都能在 2 分钟内跑完；
- 卡住时 `netstat` 里只有一条到 `172.67.161.211:443`（maven.minecraftforge.net）的 ESTABLISHED 连接。

可尝试的绕过方式（按成功率排序）：

1. **换网络/挂代理后再跑 `build.ps1`**（最省事，挂梯子通常一次过）。
2. 先清锁再跑：
   ```powershell
   Get-ChildItem D:\deepseek\tools\gradle-home -Recurse -Filter *.lock | Remove-Item -Force
   Remove-Item D:\deepseek\gravity-flip\.gradle -Recurse -Force
   ```
3. 把 `~/.gradle` 换成有完整缓存的机器构建。

## 安装

把 `gravityflip-1.0.0.jar` 放进 `.minecraft\mods`（或任意 Forge 1.20.1 客户端的 mods 目录）即可。
需要 Forge 1.20.1-47.x。

## 未验证项

- **没有编译通过**（见「已知问题」），因此没有任何游戏内实测。
- 六向重力的手感（尤其侧向滑行速度）需要实机调 `ACCELERATION` 常量。
- 撞天花板、卡在方块里等边界情况未测。
- 只做了单人逻辑；联机时每个客户端都会收到自己玩家的方向，理论可用但未验证。
