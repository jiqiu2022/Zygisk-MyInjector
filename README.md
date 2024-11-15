# [Zygisk-MyInjector](https://github.com/jiqiu2022/Zygisk-MyInjector)



原项目https://github.com/Perfare/Zygisk-Il2CppDumper

本项目在原项目基础上做局部更改，请支持原项目作者劳动成果

1. 安装[Magisk](https://github.com/topjohnwu/Magisk) v24以上版本并开启Zygisk
2. 生成模块
   - GitHub Actions
     1. Fork这个项目
     2. 在你fork的项目中选择**Actions**选项卡
     3. 在左边的侧边栏中，单击**Build**
     4. 选择**Run workflow**
     5. 输入游戏包名并点击**Run workflow**
     6. 等待操作完成并下载
   - Android Studio
     1. 下载源码
     2. 编辑`game.h`, 修改`GamePackageName`为游戏包名
     3. 使用Android Studio运行gradle任务`:module:assembleRelease`编译，zip包会生成在`out`文件夹下
3. 在Magisk里安装模块

4. 将要注入的so放入到/data/local/tmp下修改为test.so

   (部分手机第一次注入不会成功，请重启，再之后的注入会成功)

目前正在开发的分支：

​	1. 使用Java的System.load加载so

​	2. 注入多个so的分支

计划开发：

1. 第一步，仿照Riru，将注入的so进行内存上的初步隐藏（可以对抗部分业务检测，游戏安全相关已经补齐，建议不要尝试）
2. 第二步，实现一个自定义的linker，进行更深层次的注入隐藏
3. 第三步，搭配对应配套手机的内核模块对注入的模块进行进一步完美擦除，达到完美注入的目的

以此项目为脚手架的计划开发：

1. 一个全新的Frida框架，保留大部分原生api，并可以过任何相关注入检测

2. 一个全新的Trace框架，高性能Trace，速度是Stallker的60倍，并且支持更全面的信息打印。（具体效果可以参考看雪帖子）

3. 一个全新的无痕调试框架，支持像GDB一样调试，没有ptrace痕迹，两种思路进行无痕调试（基于硬件断点以及基于VM）

   