# LightingLuminol Test Datapack
# 综合测试 /scoreboard /data /schedule /item /function 命令
# /loot 单独测试（Folia loot spawn 有 bug，放单独函数避免阻断）
# 无玩家也能测试（使用 storage 和 #server 记分板）

say "=== [LightingLuminol 测试数据包] 开始测试 ==="

# 0. forceload 确保区块加载（item 测试需要）
forceload add 0 0

# 1. /scoreboard 测试
say "--- 测试 1: /scoreboard ---"
scoreboard objectives add test_kills dummy "测试击杀数"
scoreboard objectives add test_deaths deathCount "测试死亡数"
scoreboard objectives add test_dummy dummy "测试 dummy"
scoreboard players set #server test_kills 0
scoreboard players add #server test_kills 5
scoreboard players set #server test_dummy 42
tellraw @a {"text":"[scoreboard] 已创建 3 个 objective，#server test_kills=5","color":"gold"}

# 2. /data 测试 - storage
say "--- 测试 2: /data storage ---"
data modify storage test:main test_value set value "hello_from_datapack"
data modify storage test:main test_number set value 42
tellraw @a {"text":"[data] 已写入 storage test:main","color":"light_purple"}

# 3. /schedule 测试
say "--- 测试 3: /schedule ---"
schedule function test:delayed 5s replace
tellraw @a {"text":"[schedule] 已安排 5 秒后执行 test:delayed","color":"red"}

# 4. /item 测试 - forceload 已加载区块
say "--- 测试 4: /item ---"
setblock 0 100 0 minecraft:chest
item replace block 0 100 0 container.0 with minecraft:diamond_sword
item replace block 0 100 0 container.1 with minecraft:netherite_helmet
tellraw @a {"text":"[item] 已在 (0,100,0) 容器放置物品","color":"yellow"}

say "=== [LightingLuminol 测试数据包] 所有命令测试完毕 ==="
