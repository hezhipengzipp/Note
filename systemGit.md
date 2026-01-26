# Android SystemUI & Linux 全栈开发手册

**版本**: 1.0
**适用场景**: Windows 本地开发、VPS 远程部署、Android SystemUI 源码修改

---

## 目录
1. [Linux (Ubuntu) 生存指南](#1-linux-ubuntu-生存指南)
2. [远程开发环境配置 (VPS)](#2-远程开发环境配置-vps)
3. [Android 源码管理 (Repo & Git)](#3-android-源码管理-repo--git)
4. [SystemUI 开发速查](#4-systemui-开发速查)
5. [Windows + WSL 混合开发核心规范](#5-windows--wsl-混合开发核心规范)

---

## 1. Linux (Ubuntu) 生存指南

### 1.1 核心概念
* **VPS (Virtual Private Server)**: 云端的虚拟电脑（硬件资源）。
* **Ubuntu**: 运行在 VPS 上的操作系统（管家）。
* **文件系统**: 没有 C/D 盘，只有根目录 `/`。
    * `~` (即 `/home/username`): 用户主目录，代码通常放这里。
    * `/var/www`: 网站部署目录。
    * `/etc`: 系统配置文件目录。

### 1.2 高频命令速查

| 场景 | 命令 | 说明 | Android Studio 类比 |
| :--- | :--- | :--- | :--- |
| **导航** | `cd <目录>` / `cd ..` | 进入目录 / 返回上一级 | 点击文件夹 |
| **查看** | `ls -alt` | 列出文件 (按时间排序) | Project 面板 |
| **路径** | `pwd` | 显示当前位置 | 顶部导航栏 |
| **文件操作** | `cp -r <源> <目标>` | 复制文件夹 | Ctrl+C/V |
| **移动/重命名** | `mv <旧> <新>` | 移动或重命名 | Refactor -> Rename |
| **查看内容** | `tail -f <日志文件>` | **实时监控日志** | Logcat |
| **权限** | `chmod +x <文件>` | 赋予执行权限 | Gradle sync 修复权限 |
| **管理员** | `sudo <命令>` | 以管理员身份运行 | 手机 Root |

---

## 2. 远程开发环境配置 (VPS)

告别 Vim，使用 VS Code 进行远程开发是最佳实践。

### 2.1 连接方式 (VS Code Remote SSH)
1.  安装插件：**Remote - SSH** (Microsoft)。
2.  点击左下角绿色图标 `><` -> `Connect to Host`。
3.  输入：`ssh root@你的IP`。
4.  **注意**：连接后默认是空的，必须点击左边栏的 **Open Folder** 打开具体目录（如 `/home/user`）。

### 2.2 常用服务管理
Ubuntu 使用 `systemd` 管理后台服务（如 Nginx）。
```bash
systemctl status nginx   # 查看状态
systemctl restart nginx  # 重启服务
systemctl enable nginx   # 开机自启
```

## 3 Windows + WSL 混合开发核心规范
### 3.1 git拉取指定目录
使用 Sparse Checkout 只拉取部分
```bash
mkdir SystemUI_Only && cd SystemUI_Only
git init
git remote add origin <仓库地址>
git config core.sparseCheckout true
# 写入路径（不带开头的 /）
echo "frameworks/base/packages/SystemUI" >> .git/info/sparse-checkout
git pull origin master
```
### 3.2 环境搭建
1、安装 WSL 2: PowerShell 执行 wsl --install。

2、配置 Git (在 WSL 终端中执行):
```bash
git config --global --add safe.directory "*"   # 解决 Unsafe Repository
git config --global core.filemode false        # 忽略权限位变化
git config --global core.autocrlf input        # 防止换行符冲突
```
### 3.3 正确的工作流
开发工具: 使用 VS Code 安装 WSL 插件。
打开方式:
打开 WSL 终端。

cd /mnt/d/code/Project

输入 code . 启动 VS Code。

禁止事项 ❌:

禁止使用 Windows CMD/PowerShell 敲 git 命令。

禁止使用 TortoiseGit (小乌龟) 操作该仓库。

禁止在 Windows 下解压覆盖源码（会破坏软链接）。