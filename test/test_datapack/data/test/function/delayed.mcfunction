# LightingLuminol Test Datapack
# 由 test:main 调度，延迟 5 秒执行
# 用法：/function test:delayed

say "[test:delayed] 定时触发成功！/schedule + /function 工作正常"
tellraw @a {"text":"[schedule+function] 5 秒定时触发成功！","color":"green"}
