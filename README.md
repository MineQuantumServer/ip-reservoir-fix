# Immersive Petroleum Reservoir Fix

通过 Mixin 修复沉浸原油世界生成时使用 `ServerLevel.getBiome()`，在共享锁内同步等待区块的问题。独立补丁，不替换原版沉浸原油 JAR。

## 适用版本

- Minecraft **1.21.1**、Java **21**。
- NeoForge **21.1.251** 为编译和测试版本；元数据允许 `[21.1.251,21.2)`，其他版本未实测。
- Immersive Petroleum **4.5.0-39**，必须保留原模组及其沉浸工程依赖。
- 测试使用 Immersive Engineering **12.4.2-194**。
- 模组 ID：`ip_reservoir_fix`；补丁版本：`1.0.0`。
- 不支持 Fabric 或 Minecraft 1.20.1。严格限制沉浸原油版本，升级原模组后应重新核对注入点。

## 安装

1. 停服，备份存档。
2. 将 **`ip-reservoir-fix-1.21.1-1.0.0.jar`** 放入服务端 `mods` 目录。不要放入 `plugins`。
3. 保留原来的 Immersive Petroleum、Immersive Engineering，完整重启服务器。
4. 日志应出现 `[IP Reservoir Fix] Loaded 1.0.0 for Immersive Petroleum 4.5.0-39`，且没有此补丁的 Mixin 注入失败。
5. 在存档测试副本中复测下界传送和新区域生成。使用同一份备份进行对照，避免已生成区块影响结果。

多人服务器仅服务端需要补丁，不注册网络协议，客户端不要求安装。单人游戏需要放入客户端 `mods`，以修复内置服务器。单人客户端启动尚未实测。

无配置项；不要求关闭 Lithium 的区块访问优化。卸载时停服并移除补丁 JAR 即可，补丁不增加存档格式。此交付没有修改你的游戏安装目录、原模组或服务器配置。

## 修复机制

目标类：`flaxbeard.immersivepetroleum.common.world.FeatureReservoir`。

1. 在 `place` 调用 `scanChunkForNewReservoirs` 的位置，用 `@Redirect` 保存该次 `FeaturePlaceContext.level()`。
2. 只在扫描中的 `ServerLevel.getBiome(BlockPos)` 调用点注入第二个 `@Redirect`，改用这次生成的 `WorldGenLevel.getBiome(BlockPos)`。
3. 正常世界生成时，该上下文是 `WorldGenRegion`，查询通过其生成区域访问区块。坐标仍为原逻辑的 `(x, 64, z)`，保留标准生物群系采样，而非直接替换成未缩放的噪声查询。
4. 上下文按线程隔离，使用 `try/finally` 恢复嵌套调用或清除引用，不将生成区域残留在复用的工作线程上。

原来的 `generatedReservoirChunks` 共享锁、重复检查、随机数调用、油藏权重、轮廓生成和存储逻辑均保留。生物群系读取失败时传播异常，不回退到可能重新卡住的 `ServerLevel` 查询。外部直接调用公开扫描 API、没有经过 `place` 时保留原语义，这类调用不在本补丁修复范围。

两处注入均设置 `require=1, expect=1, allow=1`，配置为 `required=true`。目标不匹配会明确启动失败，不会静默失效。没有复制或打包原模组实现。

## 构建与测试

普通构建不需要原模组 JAR：

```powershell
# 设置 JAVA_HOME 为本机 JDK 21 的目录，然后执行：
.\gradlew.bat build
```

产物位于 `build/libs/`。首次构建需要网络下载 Gradle、NeoForge 和测试依赖。

运行可选游戏内回归测试时，将合法取得的下列原 JAR 放入 `smoke-mods/`：

- ImmersivePetroleum-1.21.1-4.5.0-39.jar
- ImmersiveEngineering-1.21.1-12.4.2-194.jar
- 可选：lithium-neoforge-0.15.4+mc1.21.1.jar

```powershell
.\gradlew.bat runGameTestServer
```

测试创建独立的 `run-smoke/` 测试世界，不使用正式存档。`src/smoke` 是开发测试模组，不会进入发布 JAR。测试验证实际 Mixin 转换后的方法、异常清理、油藏写入、重复抑制，以及下界真实新区块生成。JUnit 另外覆盖双线程隔离、线程复用和嵌套异常恢复。

具体验证结果及局限见随交付附带的 `验证报告.md`。完整 Youer + 星辉魔法 + 全部整合包组合及原事故存档尚未复现；不能据此承诺修复其他来源的区块死锁。

## 对照依据

已直接检查用户提供 JAR 的字节码，确认两处调用点和共享锁：

```text
ImmersivePetroleum-1.21.1-4.5.0-39.jar
SHA-256: c1bfef3290dd8c2c68519155e395059766f5d258a52d5e08faf63eb627312e8b
```

公开源码对照：[FeatureReservoir.java](https://github.com/TwistedGate/ImmersivePetroleum/blob/1.21.1/src/main/java/flaxbeard/immersivepetroleum/common/world/FeatureReservoir.java)。元数据结构参照 [NeoForge 1.21.1 文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/)。判断以用户实际 JAR 字节码为准，不假定公开分支与整份 JAR 完全一致。

补丁代码使用 MIT 许可。Immersive Petroleum、Immersive Engineering、Lithium、Minecraft 各自保留原许可；交付包不含这些原模组或游戏文件。
