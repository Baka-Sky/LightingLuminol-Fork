# 单独测试 /loot 命令
# 注意：Folia 中 loot spawn 可能有 NullPointerException bug
say "--- loot 测试开始 ---"
forceload add 0 0
loot spawn 0 100 0 loot minecraft:chests/simple_dungeon
say "--- loot 测试结束 ---"
